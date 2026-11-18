package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.diafarms.ml.commons.AbonnementEcheance;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.EnvoiAutomatiqueRepo;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.services.EmailService;

import lombok.extern.slf4j.Slf4j;

// Fermes vides inactives : statistiques de la console et suppression automatique.
//
// Ferme VIDE : aucune ligne dans les tables de données de la ferme (poulailler, Projet,
// saisie, client, magasin...). Seules comptent pas : utilisateurs, abonnement, envois
// automatiques, essai, journal et réglages (voir NON_DONNEES).
// Jamais concernées : ferme de démonstration (exclure_statistiques), ferme qui a déjà payé,
// ferme liée à un parrainage, ferme avec un compte SUPER_ADMIN.
//
// Inactivité = jours depuis la dernière connexion web, ou depuis l'inscription si personne
// ne s'est jamais connecté. Se connecter remet donc le compteur à zéro.
//   AVERTISSEMENT_JOURS : e-mail « votre ferme sera supprimée le ... » (une fois par période
//                         d'inactivité : cle = date de départ) ;
//   SUPPRESSION_JOURS   : suppression, au plus tôt DELAI_APRES_AVERTISSEMENT jours après
//                         l'avertissement (jamais de suppression sans prévenir).
// La ligne essais_gratuits de la ferme supprimée est effacée aussi : la personne peut se
// réinscrire avec un nouvel essai (elle n'avait rien utilisé).
//
// Mode (AbonnementConfig.suppressionInactifsActive) : désactivé = liste seulement dans la
// console, aucun e-mail, aucune suppression.
@Service
@Slf4j
public class FermesInactivesService {

    public static final int AVERTISSEMENT_JOURS = 23;
    public static final int SUPPRESSION_JOURS = 30;
    public static final int DELAI_APRES_AVERTISSEMENT = 7;
    static final String TYPE = "INACTIF_AVERT";
    static final String LIEN_SITE = "https://app.cocoricoferme.com/login";
    static final String LIEN_VIDEO = "https://www.youtube.com/watch?v=xkBuuq9Qqyw";

    // Tables avec farm_id qui ne sont pas des données saisies par la ferme.
    private static final Set<String> NON_DONNEES = Set.of("utilisateurs", "abonnements", "envois_automatiques",
            "essais_gratuits", "logs", "farm_app_settings", "idempotency_requests", "notes_admin_ferme",
            "modes_paiement_ferme");

    private final JdbcTemplate jdbc;
    private final GuideDemarrageService guide;
    private final AdminConsoleService console;
    private final AbonnementConfigRepo configRepo;
    private final FarmsRepo farmsRepo;
    private final EnvoiAutomatiqueRepo envoiRepo;
    private final EmailService emailService;
    private final DestinatairesAdmin destinataires;
    private final TransactionTemplate tx;

    public FermesInactivesService(JdbcTemplate jdbc, GuideDemarrageService guide, AdminConsoleService console,
            AbonnementConfigRepo configRepo, FarmsRepo farmsRepo, EnvoiAutomatiqueRepo envoiRepo,
            EmailService emailService, DestinatairesAdmin destinataires, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.guide = guide;
        this.console = console;
        this.configRepo = configRepo;
        this.farmsRepo = farmsRepo;
        this.envoiRepo = envoiRepo;
        this.emailService = emailService;
        this.destinataires = destinataires;
        this.tx = new TransactionTemplate(tm);
    }

    public record Statistiques(int total, int jamaisConnectees, int connecteesSansRien, int commenceesSansSaisie,
            int actives) {}

    public record FermeInactive(String farmUniqueId, String nom, String email, String telephone,
            boolean jamaisConnectee, LocalDate inscription, LocalDate derniereConnexion, int joursInactivite,
            LocalDate avertieLe, LocalDate suppressionLe) {}

    public record Etat(boolean suppressionActive, int joursAvertissement, int joursSuppression,
            Statistiques statistiques, List<FermeInactive> fermes) {}

    public record ResultatTache(int avertissements, int suppressions) {}

    private record Ligne(Long id, String uid, String nom, String telephone, LocalDate inscription,
            LocalDate derniereConnexion) {}

    // ------------------------------------------------------------------ console

    public Etat etat() {
        console.verifierSuperAdmin();
        return calculer(LocalDate.now());
    }

    public Etat changerMode(boolean active) {
        console.verifierSuperAdmin();
        tx.executeWithoutResult(s -> {
            AbonnementConfig c = configRepo.findFirstByOrderByIdAsc();
            c.setSuppressionInactifsActive(active);
            configRepo.save(c);
        });
        log.info("Suppression automatique des fermes inactives : {}", active ? "activée" : "désactivée");
        return calculer(LocalDate.now());
    }

    // ------------------------------------------------------------------ tâche quotidienne

    @Scheduled(cron = "${croissance.inactifs.cron:0 0 10 * * *}", zone = "Africa/Bamako")
    public void tacheQuotidienne() {
        try {
            ResultatTache r = executer(LocalDate.now());
            if (r.avertissements() + r.suppressions() > 0) {
                log.info("Fermes inactives : {} avertissement(s), {} suppression(s)", r.avertissements(), r.suppressions());
            }
        } catch (Exception e) {
            log.error("Tâche des fermes inactives en échec : {}", e.getMessage(), e);
        }
    }

    public ResultatTache executer(LocalDate auj) {
        if (!modeActif()) return new ResultatTache(0, 0);
        int avertissements = 0, suppressions = 0;
        for (FermeInactive f : calculer(auj).fermes()) {
            com.diafarms.ml.models.Farm farm = farmsRepo.findByUniqueId(f.farmUniqueId());
            Long farmId = farm == null ? null : farm.getId();
            if (farmId == null) continue;
            try {
                if (f.avertieLe() == null && f.joursInactivite() >= AVERTISSEMENT_JOURS) {
                    if (avertir(farmId, f, auj)) avertissements++;
                } else if (f.avertieLe() != null && f.suppressionLe() != null && !auj.isBefore(f.suppressionLe())) {
                    supprimer(farmId);
                    suppressions++;
                }
            } catch (Exception e) {
                log.error("Ferme inactive {} : {}", f.nom(), e.getMessage(), e);
            }
        }
        return new ResultatTache(avertissements, suppressions);
    }

    private boolean avertir(Long farmId, FermeInactive f, LocalDate auj) {
        String cle = cle(f);
        Integer reserve = tx.execute(s -> envoiRepo.reserver(farmId, TYPE, cle));
        if (reserve == null || reserve == 0) return false;
        LocalDate le = auj.plusDays(DELAI_APRES_AVERTISSEMENT);
        List<DestinatairesAdmin.Destinataire> dest = destinataires.parFerme(List.of(farmId)).getOrDefault(farmId, List.of());
        int n = 0;
        for (DestinatairesAdmin.Destinataire d : dest) {
            try {
                if (emailService.sendMessageCocorico(d.email(), d.nom(), "Votre compte",
                        "Votre ferme Cocorico sera supprimée le " + AbonnementEcheance.date(le), message(f.nom(), le))) n++;
            } catch (Exception e) {
                log.error("Avertissement d'inactivité à {} en échec : {}", d.email(), e.getMessage());
            }
        }
        final int nb = n;
        tx.execute(s -> envoiRepo.enregistrer(farmId, TYPE, cle, dest.size(), nb));
        return true;
    }

    static String message(String ferme, LocalDate le) {
        return "La ferme " + ferme + " n'a encore rien enregistré sur Cocorico. Sans connexion de votre part, "
                + "elle sera supprimée le " + AbonnementEcheance.date(le) + ".\n\n"
                + "Pour la garder, connectez-vous simplement sur " + LIEN_SITE
                + " avec les accès reçus par e-mail (sur ordinateur ou dans le navigateur du téléphone).\n\n"
                + "Découvrez Cocorico en vidéo : " + LIEN_VIDEO + "\n\n"
                + "Besoin d'aide ? Écrivez-nous sur WhatsApp au " + EssaiEmailsService.WHATSAPP + ".";
    }

    private void supprimer(Long farmId) {
        tx.executeWithoutResult(s -> {
            com.diafarms.ml.models.Farm farm = farmsRepo.findById(farmId).orElseThrow();
            // Revérifié dans la transaction : la ferme a pu commencer depuis le calcul.
            if (!videsParmi(List.of(farmId)).contains(farmId) || protegees().contains(farmId)) return;
            console.effacerFerme(farm, null, "Suppression automatique (ferme vide inactive)");
            jdbc.update("DELETE FROM essais_gratuits WHERE farm_id = ?", farmId);
        });
    }

    // ------------------------------------------------------------------ calcul

    private boolean modeActif() {
        AbonnementConfig c = configRepo.findFirstByOrderByIdAsc();
        return c != null && Boolean.TRUE.equals(c.getSuppressionInactifsActive());
    }

    private static String cle(FermeInactive f) {
        return "inactif:" + (f.derniereConnexion() != null ? f.derniereConnexion() : f.inscription());
    }

    Etat calculer(LocalDate auj) {
        boolean actif = modeActif();
        List<Ligne> lignes = lignes();
        List<Long> ids = lignes.stream().map(Ligne::id).toList();
        Set<Long> vides = videsParmi(ids);
        Set<Long> protegees = protegees();
        Map<Long, GuideDemarrageService.Etapes> etapes = guide.etapes(null);

        int jamais = 0, connecteesVides = 0, commencees = 0, actives = 0;
        for (Ligne l : lignes) {
            switch (situation(l, vides, etapes)) {
                case JAMAIS_CONNECTEE -> jamais++;
                case CONNECTEE_SANS_RIEN -> connecteesVides++;
                case COMMENCE_SANS_SAISIE -> commencees++;
                default -> actives++;
            }
        }

        // Avertissements déjà envoyés : (farm_id, cle) -> date.
        Map<String, LocalDate> avertis = new HashMap<>();
        jdbc.query("SELECT farm_id, cle, envoye_le FROM envois_automatiques WHERE type = ?", rs -> {
            avertis.put(rs.getLong(1) + ":" + rs.getString(2), rs.getTimestamp(3).toLocalDateTime().toLocalDate());
        }, TYPE);
        List<Long> candidats = lignes.stream().filter(l -> vides.contains(l.id()) && !protegees.contains(l.id()))
                .map(Ligne::id).toList();
        Map<Long, List<DestinatairesAdmin.Destinataire>> emails = candidats.isEmpty() ? Map.of() : destinataires.parFerme(candidats);

        List<FermeInactive> fermes = new ArrayList<>();
        for (Ligne l : lignes) {
            if (!candidats.contains(l.id())) continue;
            LocalDate depart = l.derniereConnexion() != null ? l.derniereConnexion() : l.inscription();
            if (depart == null) continue;
            int jours = (int) ChronoUnit.DAYS.between(depart, auj);
            LocalDate avertie = avertis.get(l.id() + ":inactif:" + depart);
            LocalDate suppression = depart.plusDays(SUPPRESSION_JOURS);
            if (avertie != null && suppression.isBefore(avertie.plusDays(DELAI_APRES_AVERTISSEMENT))) {
                suppression = avertie.plusDays(DELAI_APRES_AVERTISSEMENT);
            } else if (avertie == null && actif) {
                // Pas encore averti : au plus tôt 7 jours après l'avertissement de demain matin.
                LocalDate avertProchain = depart.plusDays(AVERTISSEMENT_JOURS).isAfter(auj)
                        ? depart.plusDays(AVERTISSEMENT_JOURS) : auj.plusDays(1);
                if (suppression.isBefore(avertProchain.plusDays(DELAI_APRES_AVERTISSEMENT))) {
                    suppression = avertProchain.plusDays(DELAI_APRES_AVERTISSEMENT);
                }
            }
            List<DestinatairesAdmin.Destinataire> d = emails.getOrDefault(l.id(), List.of());
            fermes.add(new FermeInactive(l.uid(), l.nom(), d.isEmpty() ? null : d.get(0).email(), l.telephone(),
                    l.derniereConnexion() == null, l.inscription(), l.derniereConnexion(), jours, avertie, suppression));
        }
        fermes.sort(Comparator.comparing(FermeInactive::suppressionLe));
        return new Etat(actif, AVERTISSEMENT_JOURS, SUPPRESSION_JOURS,
                new Statistiques(lignes.size(), jamais, connecteesVides, commencees, actives), fermes);
    }

    private List<Ligne> lignes() {
        return jdbc.query("SELECT f.id, f.unique_id, "
                + "COALESCE(NULLIF(f.nom, ''), (SELECT u2.farm_name FROM utilisateurs u2 WHERE u2.farm_id = f.id "
                + "  AND u2.farm_name IS NOT NULL ORDER BY u2.id LIMIT 1), 'Sans nom'), f.telephone1, "
                + "(SELECT MIN(u.created_at) FROM utilisateurs u WHERE u.farm_id = f.id), "
                + "(SELECT MAX(u.last_login) FROM utilisateurs u WHERE u.farm_id = f.id) "
                + "FROM farms f WHERE COALESCE(f.exclure_statistiques, false) = false",
                (rs, i) -> {
                    java.sql.Timestamp c = rs.getTimestamp(5), l = rs.getTimestamp(6);
                    return new Ligne(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            c != null ? c.toLocalDateTime().toLocalDate() : null,
                            l != null ? l.toLocalDateTime().toLocalDate() : null);
                });
    }

    public enum Situation { JAMAIS_CONNECTEE, CONNECTEE_SANS_RIEN, COMMENCE_SANS_SAISIE, UTILISE }

    private static Situation situation(Ligne l, Set<Long> vides, Map<Long, GuideDemarrageService.Etapes> etapes) {
        if (vides.contains(l.id())) {
            return l.derniereConnexion() == null ? Situation.JAMAIS_CONNECTEE : Situation.CONNECTEE_SANS_RIEN;
        }
        return etapes.getOrDefault(l.id(), GuideDemarrageService.Etapes.vide()).saisie()
                ? Situation.UTILISE : Situation.COMMENCE_SANS_SAISIE;
    }

    // Situation de chaque ferme hors statistiques exclues (utilisé par MessageGroupeService).
    public Map<Long, Situation> situations() {
        List<Ligne> lignes = lignes();
        Set<Long> vides = videsParmi(lignes.stream().map(Ligne::id).toList());
        Map<Long, GuideDemarrageService.Etapes> etapes = guide.etapes(null);
        Map<Long, Situation> res = new HashMap<>();
        for (Ligne l : lignes) res.put(l.id(), situation(l, vides, etapes));
        return res;
    }

    // Fermes sans aucune ligne dans les tables de données (liste calculée depuis la base :
    // une table ajoutée plus tard compte automatiquement comme donnée).
    private Set<Long> videsParmi(List<Long> ids) {
        Set<Long> res = new HashSet<>(ids);
        if (ids.isEmpty()) return res;
        List<String> tables = jdbc.queryForList("SELECT table_name FROM information_schema.columns "
                + "WHERE table_schema = 'public' AND column_name = 'farm_id'", String.class);
        StringBuilder sql = new StringBuilder();
        for (String t : tables) {
            if (NON_DONNEES.contains(t)) continue;
            if (sql.length() > 0) sql.append(" UNION ");
            sql.append("SELECT DISTINCT farm_id FROM \"").append(t).append("\" WHERE farm_id IS NOT NULL");
        }
        if (sql.length() == 0) return res;
        res.removeAll(jdbc.queryForList(sql.toString(), Long.class));
        return res;
    }

    // Jamais supprimées automatiquement : déjà payé, parrainage, super admin.
    private Set<Long> protegees() {
        Set<Long> res = new HashSet<>(jdbc.queryForList("SELECT DISTINCT a.farm_id FROM paiements_abonnement p "
                + "JOIN abonnements a ON a.id = p.abonnement_id WHERE p.statut = 'VALIDE'", Long.class));
        res.addAll(jdbc.queryForList("SELECT parrain_farm_id FROM parrainages UNION SELECT filleul_farm_id FROM parrainages", Long.class));
        res.addAll(jdbc.queryForList("SELECT u.farm_id FROM utilisateurs u JOIN roles_users ru ON ru.id_utilisateurs = u.id "
                + "JOIN roles r ON r.id = ru.id_roles WHERE UPPER(r.role) = 'SUPER_ADMIN' AND u.farm_id IS NOT NULL", Long.class));
        return res;
    }
}
