package com.diafarms.ml.ServiceImpl;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.diafarms.ml.commons.AbonnementEcheance;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Parrainage;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.repository.ParrainageRepo;
import com.diafarms.ml.services.EmailService;
import com.diafarms.ml.services.LogsServices;

import lombok.extern.slf4j.Slf4j;

// Parrainage entre fermes.
//  - Chaque ferme a un code (Farm.codeParrainage, 6 caractères sans 0/O/1/I/L), créé à la
//    première demande (page Abonnement).
//  - À l'inscription, le code (facultatif, ou ?parrain=CODE dans le lien) rattache la
//    nouvelle ferme (filleul) à la ferme du code (parrain). Règles : le code doit exister,
//    une ferme ne se parraine pas elle-même, une ferme n'est parrainée qu'une fois
//    (uk_parrainage_filleul).
//  - Récompense : au PREMIER paiement validé du filleul (validation d'une déclaration par
//    le SUPER_ADMIN, ou « Activer » de la console avec un montant reçu), le parrain gagne
//    1 mois offert : +30 jours sur sa date de fin, mêmes règles de date qu'une validation
//    (pendant la grâce on repart de l'échéance, après la grâce d'aujourd'hui). Une seule
//    fois par filleul (UPDATE ... WHERE recompense_le IS NULL), e-mail au parrain.
//  - La récompense est appliquée APRÈS le commit de la validation, dans sa propre
//    transaction : un échec ne peut jamais annuler la validation du paiement. Un
//    rattrapage quotidien (rattraper) donne la récompense oubliée (serveur arrêté entre
//    les deux, par exemple).
@Service
@Slf4j
public class ParrainageService {

    public static final int JOURS_OFFERTS = 30;
    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ParrainageRepo repo;
    private final FarmsRepo farmsRepo;
    private final JdbcTemplate jdbc;
    private final AbonnementConfigRepo configRepo;
    private final OtherService otherService;
    private final EmailService emailService;
    private final LogsServices logs;
    private final DestinatairesAdmin destinataires;
    private final com.diafarms.ml.commons.AbonnementAccesMobile accesMobile;
    private final TransactionTemplate txNouvelle;

    public ParrainageService(ParrainageRepo repo, FarmsRepo farmsRepo, JdbcTemplate jdbc, AbonnementConfigRepo configRepo,
            OtherService otherService, EmailService emailService, LogsServices logs, DestinatairesAdmin destinataires,
            com.diafarms.ml.commons.AbonnementAccesMobile accesMobile, PlatformTransactionManager tm) {
        this.repo = repo;
        this.farmsRepo = farmsRepo;
        this.jdbc = jdbc;
        this.configRepo = configRepo;
        this.otherService = otherService;
        this.emailService = emailService;
        this.logs = logs;
        this.destinataires = destinataires;
        this.accesMobile = accesMobile;
        this.txNouvelle = new TransactionTemplate(tm);
        this.txNouvelle.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ------------------------------------------------------------------ code

    public static String normaliser(String code) {
        if (code == null) return null;
        String c = code.trim().toUpperCase().replaceAll("[^A-Z0-9]", "");
        return c.isEmpty() ? null : c;
    }

    private static String nouveauCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 6; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    // Code de la ferme, créé s'il n'existe pas encore (dans la transaction de l'appelant).
    public String codeDeLaFerme(Long farmId) {
        String code = jdbc.queryForObject("SELECT code_parrainage FROM farms WHERE id = ?", String.class, farmId);
        if (code != null) return code;
        for (int essai = 0; essai < 20; essai++) {
            String c = nouveauCode();
            Integer pris = jdbc.queryForObject("SELECT COUNT(*) FROM farms WHERE code_parrainage = ?", Integer.class, c);
            if (pris != null && pris > 0) continue;
            jdbc.update("UPDATE farms SET code_parrainage = ? WHERE id = ? AND code_parrainage IS NULL", c, farmId);
            return jdbc.queryForObject("SELECT code_parrainage FROM farms WHERE id = ?", String.class, farmId);
        }
        throw new IllegalStateException("Impossible de créer le code de parrainage, réessayez.");
    }

    // Ferme du code, ou erreur lisible (inscription).
    public Farm fermeDuCode(String codeBrut) {
        String code = normaliser(codeBrut);
        if (code == null) return null;
        List<Long> ids = jdbc.queryForList("SELECT id FROM farms WHERE code_parrainage = ?", Long.class, code);
        if (ids.isEmpty()) {
            throw new IllegalArgumentException("Le code de parrainage « " + codeBrut.trim() + " » n'existe pas. "
                    + "Vérifiez le code, ou laissez la case vide.");
        }
        return farmsRepo.findById(ids.get(0)).orElse(null);
    }

    // Vérification publique depuis la page d'inscription : seulement oui / non (jamais le
    // nom de la ferme, pour ne pas laisser deviner les fermes inscrites).
    @Transactional(readOnly = true)
    public boolean codeValide(String codeBrut) {
        String code = normaliser(codeBrut);
        if (code == null || code.length() > 20) return false;
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM farms WHERE code_parrainage = ?", Integer.class, code);
        return n != null && n > 0;
    }

    // Inscription : rattache le filleul au parrain (même transaction que la création de la
    // ferme). parrain = fermeDuCode(...), vérifié AVANT la création de la ferme.
    public void enregistrer(Farm parrain, Farm filleul, String codeBrut) {
        if (parrain == null || filleul == null) return;
        if (parrain.getId().equals(filleul.getId())) {
            throw new IllegalArgumentException("Une ferme ne peut pas se parrainer elle-même.");
        }
        if (repo.findByFilleulId(filleul.getId()).isPresent()) {
            throw new IllegalArgumentException("Cette ferme a déjà un parrain.");
        }
        Parrainage p = new Parrainage();
        p.setParrain(parrain);
        p.setFilleul(filleul);
        p.setCode(normaliser(codeBrut));
        p.setCreeLe(LocalDateTime.now());
        repo.save(p);
    }

    // Ferme déjà inscrite (page Abonnement) : « Une ferme vous a invité ? Entrez son code ».
    // Seulement le propriétaire, et seulement avant tout paiement validé (sinon la
    // récompense du parrain n'aurait plus de sens).
    @Transactional
    public MonParrainageDTO saisirCode(String codeBrut) {
        Utilisateurs u = otherService.getCurrentUser();
        if (u == null || u.getFarm() == null || u.getRoles() == null
                || u.getRoles().stream().noneMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()))) {
            throw new IllegalArgumentException("Seul le propriétaire de la ferme peut saisir un code de parrainage.");
        }
        if (normaliser(codeBrut) == null) throw new IllegalArgumentException("Saisissez le code de parrainage.");
        Farm filleul = u.getFarm();
        Farm parrain = fermeDuCode(codeBrut);
        if (parrain.getId().equals(filleul.getId())) {
            throw new IllegalArgumentException("C'est le code de votre propre ferme : une ferme ne peut pas se parrainer elle-même.");
        }
        if (repo.findByFilleulId(filleul.getId()).isPresent()) {
            throw new IllegalArgumentException("Votre ferme a déjà un parrain.");
        }
        Integer payes = jdbc.queryForObject("SELECT COUNT(*) FROM paiements_abonnement p JOIN abonnements a ON a.id = p.abonnement_id "
                + "WHERE a.farm_id = ? AND p.statut = 'VALIDE'", Integer.class, filleul.getId());
        if (payes != null && payes > 0) {
            throw new IllegalArgumentException("Le code de parrainage se saisit avant le premier paiement de l'abonnement.");
        }
        try {
            enregistrer(parrain, filleul, codeBrut);
            repo.flush();
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            throw new IllegalArgumentException("Votre ferme a déjà un parrain.");
        }
        return moi();
    }

    // ------------------------------------------------------------------ page Abonnement

    public record FilleulDTO(String nom, LocalDate inscritLe, boolean recompense, LocalDateTime recompenseLe) {}

    // peutSaisirCode : la ferme n'a pas de parrain et n'a encore jamais payé.
    public record MonParrainageDTO(String code, String texteAPartager, int filleuls, int moisGagnes,
            List<FilleulDTO> liste, String parrainNom, boolean peutSaisirCode) {}

    static String texteAPartager(String code, Integer joursEssai) {
        int j = joursEssai == null || joursEssai <= 0 ? 14 : joursEssai;
        return "J'utilise Cocorico pour suivre ma ferme : ponte, aliment, ventes, tout sur le téléphone. "
                + "Inscrivez votre ferme avec mon code de parrainage " + code + " : " + j + " jours d'essai gratuit.";
    }

    @Transactional
    public MonParrainageDTO moi() {
        Utilisateurs u = otherService.getCurrentUser();
        if (u == null || u.getFarm() == null) return null;
        Long farmId = u.getFarm().getId();
        // Le code (créé à la première demande) et la liste des fermes parrainées ne
        // concernent que l'administrateur, jamais un compte en consultation seule (démo).
        boolean admin = u.getRoles() != null && u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()));
        if (!admin || Boolean.TRUE.equals(u.getConsultationSeule())) {
            throw new org.springframework.security.access.AccessDeniedException("Réservé à l'administrateur de la ferme.");
        }
        String code = codeDeLaFerme(farmId);
        Map<Long, String> noms = nomsInscription();
        List<FilleulDTO> liste = new ArrayList<>();
        int mois = 0;
        for (Parrainage p : repo.findByParrainId(farmId)) {
            boolean r = p.getRecompenseLe() != null;
            if (r) mois++;
            liste.add(new FilleulDTO(nom(p.getFilleul(), noms), p.getCreeLe().toLocalDate(), r, p.getRecompenseLe()));
        }
        String parrainNom = repo.findByFilleulId(farmId).map(p -> nom(p.getParrain(), noms)).orElse(null);
        Integer payes = jdbc.queryForObject("SELECT COUNT(*) FROM paiements_abonnement p JOIN abonnements a ON a.id = p.abonnement_id "
                + "WHERE a.farm_id = ? AND p.statut = 'VALIDE'", Integer.class, farmId);
        boolean peutSaisir = parrainNom == null && (payes == null || payes == 0);
        com.diafarms.ml.models.AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        return new MonParrainageDTO(code, texteAPartager(code, config != null ? config.getDureeEssaiJours() : null), liste.size(), mois, liste, parrainNom, peutSaisir);
    }

    // ------------------------------------------------------------------ console SUPER_ADMIN

    public record LigneParrainage(String parrainUniqueId, String parrainNom, String filleulUniqueId, String filleulNom,
            String code, LocalDateTime creeLe, LocalDateTime recompenseLe, Integer recompenseJours,
            LocalDate parrainDateFinAvant, LocalDate parrainDateFinApres) {}

    public record ParrainageFerme(String code, LigneParrainage parrain, List<LigneParrainage> filleuls) {}

    private LigneParrainage ligne(Parrainage p, Map<Long, String> noms) {
        return new LigneParrainage(p.getParrain().getUniqueId(), nom(p.getParrain(), noms), p.getFilleul().getUniqueId(),
                nom(p.getFilleul(), noms), p.getCode(), p.getCreeLe(), p.getRecompenseLe(), p.getRecompenseJours(),
                p.getParrainDateFinAvant(), p.getParrainDateFinApres());
    }

    // Fiche d'une ferme dans la console (dans la transaction de lecture de l'appelant).
    public ParrainageFerme pourLaConsole(Farm f) {
        Map<Long, String> noms = nomsInscription();
        LigneParrainage parrain = repo.findByFilleulId(f.getId()).map(p -> ligne(p, noms)).orElse(null);
        List<LigneParrainage> filleuls = repo.findByParrainId(f.getId()).stream().map(p -> ligne(p, noms)).toList();
        return new ParrainageFerme(f.getCodeParrainage(), parrain, filleuls);
    }

    // Tous les parrainages (onglet de la console), plus récents d'abord.
    @Transactional(readOnly = true)
    public List<LigneParrainage> tous() {
        Map<Long, String> noms = nomsInscription();
        return repo.findAll(org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "creeLe"))
                .stream().map(p -> ligne(p, noms)).toList();
    }

    private Map<Long, String> nomsInscription() {
        Map<Long, String> m = new java.util.HashMap<>();
        jdbc.query("SELECT DISTINCT ON (farm_id) farm_id, farm_name FROM utilisateurs "
                + "WHERE farm_id IS NOT NULL AND farm_name IS NOT NULL ORDER BY farm_id, id", rs -> {
                    m.put(rs.getLong(1), rs.getString(2));
                });
        return m;
    }

    private static String nom(Farm f, Map<Long, String> noms) {
        if (f.getNom() != null && !f.getNom().isBlank()) return f.getNom();
        String n = noms.get(f.getId());
        return n != null ? n : f.getUniqueId();
    }

    // ------------------------------------------------------------------ récompense

    // À appeler depuis une validation de paiement (dans sa transaction) : la récompense
    // est donnée après le commit, jamais si la validation est annulée.
    public void apresPaiementValide(Long filleulFarmId, Long paiementId, Long superAdminId) {
        Runnable action = () -> {
            try {
                recompenser(filleulFarmId, paiementId, superAdminId);
            } catch (Exception e) {
                log.error("Parrainage : récompense non appliquée pour la ferme {} (sera rattrapée) : {}", filleulFarmId, e.getMessage(), e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private record Recompense(Long parrainFarmId, String parrainNom, String filleulNom, String code,
            LocalDate avant, LocalDate apres) {}

    // Applique la récompense si le filleul a un parrain non encore récompensé et au moins un
    // paiement validé. true si la récompense vient d'être donnée.
    public boolean recompenser(Long filleulFarmId, Long paiementId, Long superAdminId) {
        Recompense r = txNouvelle.execute(status -> {
            List<Map<String, Object>> lignes = jdbc.queryForList(
                    "SELECT p.id, p.parrain_farm_id, p.code FROM parrainages p WHERE p.filleul_farm_id = ? AND p.recompense_le IS NULL",
                    filleulFarmId);
            if (lignes.isEmpty()) return null;
            Long id = ((Number) lignes.get(0).get("id")).longValue();
            Long parrainId = ((Number) lignes.get(0).get("parrain_farm_id")).longValue();
            String code = (String) lignes.get(0).get("code");
            // Premier paiement validé du filleul (le paiement qui déclenche, ou le plus ancien).
            List<Long> paiements = jdbc.queryForList("SELECT p.id FROM paiements_abonnement p JOIN abonnements a ON a.id = p.abonnement_id "
                    + "WHERE a.farm_id = ? AND p.statut = 'VALIDE' ORDER BY p.date_validation, p.id", Long.class, filleulFarmId);
            if (paiements.isEmpty()) return null;
            // Parrain suspendu, hors statistiques (démo) ou sans abonnement : la récompense reste
            // en attente ; le rattrapage quotidien la donnera quand la situation le permet.
            List<Map<String, Object>> etatParrain = jdbc.queryForList(
                    "SELECT COALESCE(a.suspendu, false) AS suspendu, COALESCE(f.exclure_statistiques, false) AS exclue "
                    + "FROM farms f LEFT JOIN abonnements a ON a.farm_id = f.id WHERE f.id = ?", parrainId);
            if (etatParrain.isEmpty() || Boolean.TRUE.equals(etatParrain.get(0).get("suspendu"))
                    || Boolean.TRUE.equals(etatParrain.get(0).get("exclue"))
                    || jdbc.queryForObject("SELECT COUNT(*) FROM abonnements WHERE farm_id = ?", Integer.class, parrainId) == 0) {
                return null;
            }
            if (repo.reserverRecompense(id) == 0) return null; // déjà donnée (exécution concurrente)

            // Abonnement du parrain verrouillé : une action simultanée passe avant ou après.
            List<Map<String, Object>> abos = jdbc.queryForList(
                    "SELECT id, date_fin FROM abonnements WHERE farm_id = ? FOR UPDATE", parrainId);
            LocalDate avant = null, apres = null;
            if (!abos.isEmpty()) {
                Long aboId = ((Number) abos.get(0).get("id")).longValue();
                avant = ((java.sql.Date) abos.get(0).get("date_fin")).toLocalDate();
                LocalDate auj = LocalDate.now();
                int grace = AbonnementEcheance.delaiGraceJours(configRepo.findFirstByOrderByIdAsc());
                LocalDate base = !auj.isAfter(avant.plusDays(grace)) ? avant : auj;
                apres = base.plusDays(JOURS_OFFERTS);
                jdbc.update("UPDATE abonnements SET date_fin = ? WHERE id = ?", java.sql.Date.valueOf(apres), aboId);
                accesMobile.invaliderApresCommit(parrainId);
            } else {
                log.warn("Parrainage : la ferme marraine {} n'a pas encore d'abonnement, mois offert non appliqué", parrainId);
            }
            jdbc.update("UPDATE parrainages SET recompense_jours = ?, parrain_date_fin_avant = ?, parrain_date_fin_apres = ?, "
                    + "paiement_id = ? WHERE id = ?", JOURS_OFFERTS, avant != null ? java.sql.Date.valueOf(avant) : null,
                    apres != null ? java.sql.Date.valueOf(apres) : null,
                    paiementId != null ? paiementId : paiements.get(0), id);
            Map<Long, String> noms = nomsInscription();
            Farm parrain = farmsRepo.findById(parrainId).orElse(null);
            Farm filleul = farmsRepo.findById(filleulFarmId).orElse(null);
            String parrainNom = parrain != null ? nom(parrain, noms) : "votre ferme";
            String filleulNom = filleul != null ? nom(filleul, noms) : "une ferme";
            Long auteur = superAdminId;
            if (auteur == null) {
                List<Long> sa = jdbc.queryForList("SELECT u.id FROM utilisateurs u JOIN roles_users ru ON ru.id_utilisateurs = u.id "
                        + "JOIN roles ro ON ro.id = ru.id_roles WHERE ro.role = 'SUPER_ADMIN' ORDER BY u.id LIMIT 1", Long.class);
                auteur = sa.isEmpty() ? null : sa.get(0);
            }
            if (auteur != null) {
                try {
                    logs.addLogs(auteur, parrainId, AdminConsoleService.ENTITE_ADMIN_FERME,
                            "Parrainage : 1 mois offert à la ferme " + parrainNom + " (ferme parrainée : " + filleulNom + ")");
                } catch (Exception e) {
                    log.warn("Parrainage : journal non écrit : {}", e.getMessage());
                }
            }
            return new Recompense(parrainId, parrainNom, filleulNom, code, avant, apres);
        });
        if (r == null) return false;
        envoyerEmail(r);
        return true;
    }

    private void envoyerEmail(Recompense r) {
        try {
            String sujet = "Merci ! Vous gagnez 1 mois offert sur Cocorico";
            String message = "La ferme " + r.filleulNom() + ", que vous avez parrainée, vient de payer son abonnement.\n\n"
                    + "Pour vous remercier, nous offrons 1 mois à la ferme " + r.parrainNom() + "."
                    + (r.apres() != null ? " Votre accès va maintenant jusqu'au " + AbonnementEcheance.date(r.apres()) + "." : "")
                    + "\n\nContinuez à partager votre code " + r.code()
                    + " : chaque ferme parrainée qui paie son abonnement vous donne 1 mois de plus.\n\n"
                    + "Une question ? Écrivez-nous sur WhatsApp au " + EssaiEmailsService.WHATSAPP + ".";
            for (DestinatairesAdmin.Destinataire d : destinataires.parFerme(List.of(r.parrainFarmId())).getOrDefault(r.parrainFarmId(), List.of())) {
                try {
                    emailService.sendMessageCocorico(d.email(), d.nom(), "Parrainage", sujet, message);
                } catch (Exception e) {
                    log.warn("Parrainage : e-mail à {} non envoyé : {}", d.email(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("Parrainage : e-mails non préparés : {}", e.getMessage());
        }
    }

    // Rattrapage (tâche quotidienne des e-mails d'essai) : filleuls qui ont payé mais dont
    // le parrain n'a pas encore été récompensé.
    public int rattraper() {
        List<Long> filleuls = jdbc.queryForList("SELECT DISTINCT p.filleul_farm_id FROM parrainages p "
                + "JOIN abonnements a ON a.farm_id = p.filleul_farm_id "
                + "JOIN paiements_abonnement pa ON pa.abonnement_id = a.id AND pa.statut = 'VALIDE' "
                + "WHERE p.recompense_le IS NULL", Long.class);
        int n = 0;
        for (Long f : filleuls) {
            try {
                if (recompenser(f, null, null)) n++;
            } catch (Exception e) {
                log.error("Parrainage : rattrapage en échec pour la ferme {} : {}", f, e.getMessage());
            }
        }
        return n;
    }
}
