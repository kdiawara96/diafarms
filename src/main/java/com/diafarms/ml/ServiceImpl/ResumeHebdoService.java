package com.diafarms.ml.ServiceImpl;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
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

import com.diafarms.ml.DTO.CompteClientDTO;
import com.diafarms.ml.DTO.ReportingDTO;
import com.diafarms.ml.commons.AbonnementEcheance;
import com.diafarms.ml.commons.Devise;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.AbonnementRepo;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.EnvoiAutomatiqueRepo;
import com.diafarms.ml.services.EmailService;

import lombok.extern.slf4j.Slf4j;

// Résumé de la semaine par e-mail au propriétaire de la ferme (ADMIN), chaque lundi à
// 7 h (heure de Bamako = UTC), sur la semaine du lundi au dimanche qui vient de finir :
//   œufs (alvéoles), taux de ponte et celui de la semaine d'avant, mortalité, aliment
//   consommé, Vendu, Encaissé, dépenses, ce que les clients doivent encore, et 3 points
//   d'attention au plus.
// Chiffres : ReportingService.rapportFerme (mêmes règles que la page Reporting d'un ADMIN,
// vue ferme entière) ; reste dû des clients : CompteClientService.comptes (même calcul
// que Comptabilité, en requêtes groupées). Devise de la ferme posée pour chaque ferme
// (Devise.definir) : la tâche n'a pas d'utilisateur.
// Jamais envoyé : ferme sans activité cette semaine (aucune saisie ni transaction), ferme
// de démonstration (exclure_statistiques), ferme suspendue ou expirée, ferme qui a coupé
// le résumé dans Paramètres (Farm.resumeHebdo = false). Une seule fois par ferme et par
// semaine : ligne envois_automatiques (ferme, RESUME_SEMAINE, date du lundi).
// Performances : la sélection des fermes se fait en 4 requêtes pour toutes les fermes ; le
// calcul complet (une vingtaine de requêtes) n'est fait que pour les fermes retenues.
@Service
@Slf4j
public class ResumeHebdoService {

    public static final String TYPE = "RESUME_SEMAINE";
    public static final double PONTE_BAISSE_POINTS = 5.0;
    public static final double STOCK_JOURS_MIN = 7.0;
    public static final int MORTS_MIN_HAUSSE = 3;

    private final JdbcTemplate jdbc;
    private final AbonnementRepo abonnementRepo;
    private final AbonnementConfigRepo configRepo;
    private final ReportingService reportingService;
    private final CompteClientService compteClientService;
    private final ClientRepo clientRepo;
    private final EnvoiAutomatiqueRepo envoiRepo;
    private final EmailService emailService;
    private final OtherService otherService;
    private final DestinatairesAdmin destinataires;
    private final TransactionTemplate tx;
    private final TransactionTemplate txLecture;

    public ResumeHebdoService(JdbcTemplate jdbc, AbonnementRepo abonnementRepo, AbonnementConfigRepo configRepo,
            ReportingService reportingService, CompteClientService compteClientService, ClientRepo clientRepo,
            EnvoiAutomatiqueRepo envoiRepo, EmailService emailService, OtherService otherService,
            DestinatairesAdmin destinataires, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.abonnementRepo = abonnementRepo;
        this.configRepo = configRepo;
        this.reportingService = reportingService;
        this.compteClientService = compteClientService;
        this.clientRepo = clientRepo;
        this.envoiRepo = envoiRepo;
        this.emailService = emailService;
        this.otherService = otherService;
        this.destinataires = destinataires;
        this.tx = new TransactionTemplate(tm);
        this.txLecture = new TransactionTemplate(tm);
        this.txLecture.setReadOnly(true);
    }

    // Chiffres du résumé (renvoyés aussi par le déclenchement manuel, pour vérifier).
    public record Chiffres(long oeufs, double alveoles, Double tauxPonte, Double tauxPontePrecedent, long mortes,
            long mortesPrecedent, double alimentKg, double vendu, double encaisse, double depenses,
            double clientsDoivent, String devise) {}

    public record ResumeDTO(String farmUniqueId, String farmNom, LocalDate lundi, LocalDate dimanche, String sujet,
            String message, Chiffres chiffres, List<String> pointsAttention, List<String> destinataires,
            boolean envoye, int emailsEnvoyes) {}

    @Scheduled(cron = "${croissance.resume.cron:0 0 7 * * MON}", zone = "Africa/Bamako")
    public void tacheHebdomadaire() {
        try {
            List<ResumeDTO> res = executer(true, lundiDeLaSemainePassee(LocalDate.now()), null);
            log.info("Résumé de la semaine : {} ferme(s)", res.size());
        } catch (Exception e) {
            log.error("Tâche du résumé de la semaine en échec : {}", e.getMessage(), e);
        }
    }

    public static LocalDate lundiDeLaSemainePassee(LocalDate auj) {
        return auj.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
    }

    // Déclenchement manuel (SUPER_ADMIN), simulation par défaut. lundi = semaine à résumer
    // (null = la semaine passée), farmUniqueId = une seule ferme (facultatif).
    public List<ResumeDTO> executerManuellement(boolean envoyer, LocalDate lundi, String farmUniqueId) {
        Utilisateurs u = otherService.getCurrentUser();
        boolean superAdmin = u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
        if (!superAdmin) {
            throw new org.springframework.security.access.AccessDeniedException("Seul un SUPER_ADMIN peut effectuer cette action.");
        }
        LocalDate l = lundi != null ? lundi : lundiDeLaSemainePassee(LocalDate.now());
        if (l.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("La semaine doit commencer un lundi : " + l);
        }
        if (l.plusDays(6).isAfter(LocalDate.now())) {
            throw new IllegalArgumentException("Cette semaine n'est pas encore finie.");
        }
        return executer(envoyer, l, farmUniqueId);
    }

    private record Retenue(Long farmId, String uid, String nom, String devise) {}

    public List<ResumeDTO> executer(boolean envoyer, LocalDate lundi, String seulementFerme) {
        LocalDate dimanche = lundi.plusDays(6);
        List<Retenue> retenues = txLecture.execute(s -> retenues(lundi, dimanche, seulementFerme));
        if (retenues == null || retenues.isEmpty()) return List.of();
        Map<Long, List<DestinatairesAdmin.Destinataire>> dest = destinataires.parFerme(retenues.stream().map(Retenue::farmId).toList());
        List<ResumeDTO> res = new ArrayList<>();
        for (Retenue r : retenues) {
            List<DestinatairesAdmin.Destinataire> admins = dest.getOrDefault(r.farmId(), List.of());
            if (admins.isEmpty()) continue;
            ResumeDTO resume;
            try {
                resume = txLecture.execute(s -> {
                    Devise.definir(r.devise());
                    try {
                        return construire(r, lundi, dimanche, admins);
                    } finally {
                        Devise.effacer();
                    }
                });
            } catch (Exception e) {
                log.error("Résumé de la semaine : calcul en échec pour la ferme {} : {}", r.nom(), e.getMessage(), e);
                continue;
            }
            if (resume == null) continue;
            if (!envoyer) {
                res.add(resume);
                continue;
            }
            try {
                String cle = lundi.toString();
                Integer reserve = tx.execute(s -> envoiRepo.reserver(r.farmId(), TYPE, cle));
                if (reserve == null || reserve == 0) continue; // déjà envoyé pour cette semaine
                int n = 0;
                for (DestinatairesAdmin.Destinataire d : admins) {
                    try {
                        if (emailService.sendMessageCocorico(d.email(), d.nom(), "Résumé de la semaine", resume.sujet(), resume.message())) n++;
                    } catch (Exception e) {
                        log.error("Résumé de la semaine : e-mail à {} en échec : {}", d.email(), e.getMessage());
                    }
                }
                final int nb = n;
                tx.execute(s -> envoiRepo.enregistrer(r.farmId(), TYPE, cle, admins.size(), nb));
                res.add(new ResumeDTO(resume.farmUniqueId(), resume.farmNom(), lundi, dimanche, resume.sujet(), resume.message(),
                        resume.chiffres(), resume.pointsAttention(), resume.destinataires(), true, nb));
            } catch (Exception e) {
                log.error("Résumé de la semaine : envoi en échec pour la ferme {} : {}", r.nom(), e.getMessage(), e);
            }
        }
        return res;
    }

    // Fermes à qui envoyer le résumé de cette semaine (toutes en quelques requêtes).
    private List<Retenue> retenues(LocalDate lundi, LocalDate dimanche, String seulementFerme) {
        // Fermes avec une activité dans la semaine : saisie d'élevage ou transaction.
        Set<Long> actives = new HashSet<>(jdbc.queryForList(
                "SELECT p.farm_id FROM collectes_oeufs c JOIN projets p ON p.id = c.projet_id "
                        + "WHERE COALESCE(c.removed, false) = false AND c.date BETWEEN ? AND ? "
                        + "UNION SELECT p.farm_id FROM mortalites c JOIN projets p ON p.id = c.projet_id "
                        + "WHERE COALESCE(c.removed, false) = false AND c.date BETWEEN ? AND ? "
                        + "UNION SELECT p.farm_id FROM consommations_aliment c JOIN projets p ON p.id = c.projet_id "
                        + "WHERE COALESCE(c.removed, false) = false AND c.date BETWEEN ? AND ? "
                        + "UNION SELECT t.farm_id FROM transactions t "
                        + "WHERE COALESCE(t.removed, false) = false AND t.date BETWEEN ? AND ?",
                Long.class, lundi, dimanche, lundi, dimanche, lundi, dimanche, lundi, dimanche));
        if (actives.isEmpty()) return List.of();
        Set<Long> deja = new HashSet<>(jdbc.queryForList(
                "SELECT farm_id FROM envois_automatiques WHERE type = ? AND cle = ?", Long.class, TYPE, lundi.toString()));
        Map<Long, String> noms = new HashMap<>();
        jdbc.query("SELECT DISTINCT ON (farm_id) farm_id, farm_name FROM utilisateurs "
                + "WHERE farm_id IS NOT NULL AND farm_name IS NOT NULL ORDER BY farm_id, id", rs -> {
                    noms.put(rs.getLong(1), rs.getString(2));
                });
        AbonnementConfig config = configRepo.findFirstByOrderByIdAsc();
        LocalDate auj = LocalDate.now();
        List<Retenue> res = new ArrayList<>();
        for (Abonnement a : abonnementRepo.findAllAvecFerme()) {
            Farm f = a.getFarm();
            if (!actives.contains(f.getId()) || deja.contains(f.getId())) continue;
            if (seulementFerme != null && !seulementFerme.equals(f.getUniqueId())) continue;
            if (Boolean.TRUE.equals(f.getExclureStatistiques())) continue; // démonstration
            if (Boolean.FALSE.equals(f.getResumeHebdo())) continue; // coupé dans Paramètres
            if (AbonnementEcheance.calculer(a, config, auj).bloque()) continue; // suspendue ou expirée
            String nom = f.getNom() != null && !f.getNom().isBlank() ? f.getNom()
                    : noms.getOrDefault(f.getId(), "votre ferme");
            res.add(new Retenue(f.getId(), f.getUniqueId(), nom, f.getDevise()));
        }
        return res;
    }

    private ResumeDTO construire(Retenue r, LocalDate lundi, LocalDate dimanche, List<DestinatairesAdmin.Destinataire> admins) {
        ReportingDTO rap = reportingService.rapportFerme(r.farmId(), lundi, dimanche);
        ReportingDTO.Elevage el = rap.getPeriode().getElevage();
        ReportingDTO.Elevage elPrec = rap.getPrecedent().getElevage();
        ReportingDTO.Argent ar = rap.getPeriode().getArgent();
        List<CompteClientDTO> comptes = compteClientService.comptes(clientRepo.findAllActiveByFarmId(r.farmId()));
        double doivent = comptes.stream().mapToDouble(CompteClientDTO::getSolde).filter(x -> x > 0).sum();

        Chiffres c = new Chiffres(el.getOeufsCollectes(), el.getAlveoles(), el.getTauxPonteMoyen(),
                elPrec.getTauxPonteMoyen(), el.getMortes(), elPrec.getMortes(), el.getAlimentKg(),
                nz(ar.getVendu()), nz(ar.getEncaisse()), nz(ar.getDepenses()), com.diafarms.ml.commons.Franc.arrondi(doivent),
                Devise.courante().code());

        List<String> points = points(r.farmId(), c, dimanche);

        StringBuilder m = new StringBuilder();
        m.append("Voici le résumé de la ferme ").append(r.nom()).append(" pour la semaine du lundi ")
                .append(AbonnementEcheance.date(lundi)).append(" au dimanche ").append(AbonnementEcheance.date(dimanche)).append(".\n\n");
        m.append("Production\n");
        m.append("Œufs collectés : ").append(AbonnementEcheance.nombre(c.oeufs())).append(" (")
                .append(un(c.alveoles())).append(" alvéoles)\n");
        if (c.tauxPonte() != null) {
            m.append("Taux de ponte : ").append(un(c.tauxPonte())).append(" %")
                    .append(c.tauxPontePrecedent() != null ? " (semaine d'avant : " + un(c.tauxPontePrecedent()) + " %)" : "")
                    .append("\n");
        }
        m.append("Mortalité : ").append(c.mortes()).append(c.mortes() > 1 ? " sujets morts" : " sujet mort")
                .append(" (semaine d'avant : ").append(c.mortesPrecedent()).append(")\n");
        m.append("Aliment consommé : ").append(un(c.alimentKg())).append(" kg\n\n");
        m.append("Argent\n");
        m.append("Vendu : ").append(Devise.montant(c.vendu())).append("\n");
        m.append("Encaissé : ").append(Devise.montant(c.encaisse())).append("\n");
        m.append("Dépenses : ").append(Devise.montant(c.depenses())).append("\n");
        m.append("Vos clients vous doivent encore : ").append(Devise.montant(c.clientsDoivent())).append("\n\n");
        m.append("Points d'attention\n");
        if (points.isEmpty()) {
            m.append("Rien d'inquiétant cette semaine. Bon travail !\n\n");
        } else {
            for (String p : points) m.append("- ").append(p).append("\n");
            m.append("\n");
        }
        m.append("Pour voir le détail, ouvrez le Reporting dans l'application web.\n\n");
        m.append("Vous ne voulez plus recevoir ce résumé ? Le propriétaire de la ferme peut le couper dans Paramètres.");

        String sujet = "Votre semaine à la ferme " + r.nom() + " (du " + AbonnementEcheance.date(lundi).substring(0, 5)
                + " au " + AbonnementEcheance.date(dimanche).substring(0, 5) + ")";
        return new ResumeDTO(r.uid(), r.nom(), lundi, dimanche, sujet, m.toString(), c, points,
                admins.stream().map(DestinatairesAdmin.Destinataire::email).toList(), false, 0);
    }

    // 3 points au plus, dans cet ordre : mortalité en hausse, ponte en baisse, stock
    // d'aliment pour moins de 7 jours (projet par projet), clients qui doivent de l'argent.
    private List<String> points(Long farmId, Chiffres c, LocalDate dimanche) {
        List<String> p = new ArrayList<>();
        if (c.mortes() >= MORTS_MIN_HAUSSE && c.mortes() > c.mortesPrecedent()) {
            p.add("La mortalité monte : " + c.mortes() + " morts cette semaine contre " + c.mortesPrecedent()
                    + " la semaine d'avant.");
        }
        if (c.tauxPonte() != null && c.tauxPontePrecedent() != null
                && c.tauxPontePrecedent() - c.tauxPonte() > PONTE_BAISSE_POINTS) {
            p.add("La ponte baisse : " + un(c.tauxPonte()) + " % contre " + un(c.tauxPontePrecedent())
                    + " % la semaine d'avant.");
        }
        // Stock d'aliment de chaque Projet en cours, à la consommation moyenne de la semaine.
        jdbc.query("SELECT p.code, COALESCE(a.achete, 0) - COALESCE(c.conso, 0), COALESCE(c.semaine, 0) FROM projets p "
                + "LEFT JOIN (SELECT projet_id, SUM(quantite_kg) AS achete FROM alimentations WHERE COALESCE(removed, false) = false "
                + "  GROUP BY projet_id) a ON a.projet_id = p.id "
                + "LEFT JOIN (SELECT projet_id, SUM(quantite_kg) AS conso, "
                + "  SUM(quantite_kg) FILTER (WHERE date BETWEEN ? AND ?) AS semaine FROM consommations_aliment "
                + "  WHERE COALESCE(removed, false) = false GROUP BY projet_id) c ON c.projet_id = p.id "
                + "WHERE p.farm_id = ? AND COALESCE(p.removed, false) = false AND COALESCE(p.archive, false) = false "
                + "AND p.date_cloture IS NULL AND COALESCE(a.achete, 0) > 0 ORDER BY p.code", rs -> {
                    double restant = rs.getDouble(2);
                    double parJour = rs.getDouble(3) / 7.0;
                    if (parJour <= 0) return;
                    double jours = Math.max(0, restant) / parJour;
                    if (jours < STOCK_JOURS_MIN) {
                        long j = (long) Math.floor(jours);
                        p.add(restant <= 0 ? "Stock d'aliment épuisé pour le Projet " + rs.getString(1) + "."
                                : "Stock d'aliment bas pour le Projet " + rs.getString(1) + " : environ "
                                        + (j < 1 ? "moins d'un jour" : j + (j > 1 ? " jours" : " jour")) + " à la consommation actuelle.");
                    }
                }, dimanche.minusDays(6), dimanche, farmId);
        if (c.clientsDoivent() > 0) {
            p.add("Vos clients vous doivent " + Devise.montant(c.clientsDoivent()) + " : pensez à les relancer.");
        }
        return p.size() > 3 ? new ArrayList<>(p.subList(0, 3)) : p;
    }

    // ------------------------------------------------------------------ réglage (Paramètres)

    private Utilisateurs adminDeFerme() {
        Utilisateurs u = otherService.getCurrentUser();
        if (u == null || u.getFarm() == null || u.getRoles() == null
                || u.getRoles().stream().noneMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()))) {
            throw new IllegalArgumentException("Seul le propriétaire de la ferme peut changer ce réglage.");
        }
        return u;
    }

    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public boolean reglage() {
        Utilisateurs u = adminDeFerme();
        Boolean v = jdbc.queryForObject("SELECT resume_hebdo FROM farms WHERE id = ?", Boolean.class, u.getFarm().getId());
        return !Boolean.FALSE.equals(v);
    }

    // null en base = oui (valeur par défaut) ; seul « non » est enregistré comme false.
    @org.springframework.transaction.annotation.Transactional
    public boolean changerReglage(boolean actif) {
        Utilisateurs u = adminDeFerme();
        jdbc.update("UPDATE farms SET resume_hebdo = ? WHERE id = ?", actif ? null : Boolean.FALSE, u.getFarm().getId());
        return actif;
    }

    private static double nz(Double v) {
        return v == null ? 0 : v;
    }

    private static String un(double v) {
        double r = Math.round(v * 10) / 10.0;
        String s = r == Math.rint(r) ? String.valueOf((long) r) : String.valueOf(r).replace('.', ',');
        if (r == Math.rint(r) && Math.abs(r) >= 1000) s = AbonnementEcheance.nombre((long) r);
        return s;
    }
}
