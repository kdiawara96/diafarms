package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.EnvoiAutomatiqueRepo;
import com.diafarms.ml.services.EmailService;

import lombok.extern.slf4j.Slf4j;

// E-mails de démarrage pendant l'essai (tâche quotidienne, 9 h heure de Bamako = UTC) :
//   ESSAI_J1 : 1 ou 2 jours après l'inscription, si la ferme n'a encore aucun Projet :
//              comment démarrer en 3 étapes ;
//   ESSAI_J3 : 3 à 6 jours après, si toujours aucune saisie : relance avec l'aide WhatsApp ;
//   ESSAI_J7 : à partir du 7e jour et tant que l'essai court, si toujours aucune saisie :
//              « Votre essai se termine dans X jours, on vous aide ? ».
// Fenêtres (et non un jour exact) pour rattraper un jour où la tâche n'a pas tourné.
// Jamais envoyés : ferme déjà active (au moins une saisie), ferme de démonstration
// (exclure_statistiques), ferme suspendue, ferme qui a déjà payé, essai terminé.
// Destinataires : les ADMIN actifs de la ferme qui ont un e-mail.
// Une seule fois : ligne envois_automatiques (ferme, type, "essai") réservée AVANT l'envoi.
// Aucune dépendance à l'utilisateur courant : la tâche tourne sans requête HTTP.
// Performances : 4 requêtes au total, quel que soit le nombre de fermes.
@Service
@Slf4j
public class EssaiEmailsService {

    public static final String J1 = "ESSAI_J1";
    public static final String J3 = "ESSAI_J3";
    public static final String J7 = "ESSAI_J7";
    private static final String CLE = "essai";
    static final String WHATSAPP = "+223 83 91 86 99";

    private final JdbcTemplate jdbc;
    private final GuideDemarrageService guide;
    private final EnvoiAutomatiqueRepo envoiRepo;
    private final EmailService emailService;
    private final OtherService otherService;
    private final DestinatairesAdmin destinataires;
    private final ParrainageService parrainage;
    private final TransactionTemplate tx;
    private final TransactionTemplate txLecture;

    public EssaiEmailsService(JdbcTemplate jdbc, GuideDemarrageService guide, EnvoiAutomatiqueRepo envoiRepo,
            EmailService emailService, OtherService otherService, DestinatairesAdmin destinataires,
            ParrainageService parrainage, PlatformTransactionManager tm) {
        this.parrainage = parrainage;
        this.destinataires = destinataires;
        this.jdbc = jdbc;
        this.guide = guide;
        this.envoiRepo = envoiRepo;
        this.emailService = emailService;
        this.otherService = otherService;
        this.tx = new TransactionTemplate(tm);
        this.txLecture = new TransactionTemplate(tm);
        this.txLecture.setReadOnly(true);
    }

    public record Destinataire(String nom, String email) {}

    public record EnvoiDTO(String farmUniqueId, String farmNom, String type, int joursDepuisInscription,
            String sujet, String message, List<String> destinataires, boolean envoye, int emailsEnvoyes) {}

    private record Candidat(Long farmId, String farmUniqueId, String farmNom, String type, int jours,
            String sujet, String message, List<Destinataire> admins) {}

    @Scheduled(cron = "${croissance.essai.cron:0 0 9 * * *}", zone = "Africa/Bamako")
    public void tacheQuotidienne() {
        try {
            List<EnvoiDTO> res = executer(true, LocalDate.now());
            log.info("E-mails d'essai : {} e-mail(s) de démarrage envoyé(s)", res.size());
        } catch (Exception e) {
            log.error("Tâche des e-mails d'essai en échec : {}", e.getMessage(), e);
        }
        try {
            int n = parrainage.rattraper();
            if (n > 0) log.info("Parrainage : {} récompense(s) rattrapée(s)", n);
        } catch (Exception e) {
            log.error("Rattrapage des parrainages en échec : {}", e.getMessage(), e);
        }
    }

    // Déclenchement manuel (SUPER_ADMIN) : envoyer=false = simulation, rien n'est enregistré.
    public List<EnvoiDTO> executerManuellement(boolean envoyer) {
        Utilisateurs u = otherService.getCurrentUser();
        boolean superAdmin = u != null && u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
        if (!superAdmin) {
            throw new org.springframework.security.access.AccessDeniedException("Seul un SUPER_ADMIN peut effectuer cette action.");
        }
        return executer(envoyer, LocalDate.now());
    }

    public List<EnvoiDTO> executer(boolean envoyer, LocalDate aujourdHui) {
        List<Candidat> candidats = txLecture.execute(s -> candidats(aujourdHui));
        List<EnvoiDTO> res = new ArrayList<>();
        for (Candidat c : candidats) {
            List<String> emails = c.admins().stream().map(Destinataire::email).toList();
            if (!envoyer) {
                res.add(new EnvoiDTO(c.farmUniqueId(), c.farmNom(), c.type(), c.jours(), c.sujet(), c.message(), emails, false, 0));
                continue;
            }
            try {
                Integer reserve = tx.execute(s -> envoiRepo.reserver(c.farmId(), c.type(), CLE));
                if (reserve == null || reserve == 0) continue; // déjà envoyé
                int n = 0;
                for (Destinataire d : c.admins()) {
                    try {
                        if (emailService.sendMessageCocorico(d.email(), d.nom(), "Bien démarrer", c.sujet(), c.message())) n++;
                    } catch (Exception e) {
                        log.error("E-mail d'essai à {} en échec : {}", d.email(), e.getMessage());
                    }
                }
                final int nb = n;
                tx.execute(s -> envoiRepo.enregistrer(c.farmId(), c.type(), CLE, c.admins().size(), nb));
                res.add(new EnvoiDTO(c.farmUniqueId(), c.farmNom(), c.type(), c.jours(), c.sujet(), c.message(), emails, true, nb));
            } catch (Exception e) {
                log.error("E-mail d'essai de la ferme {} en échec : {}", c.farmNom(), e.getMessage(), e);
            }
        }
        return res;
    }

    private record Essai(Long farmId, String uid, String nom, LocalDate inscription, LocalDate dateFin) {}

    private List<Candidat> candidats(LocalDate auj) {
        // 1. Fermes en essai : jamais payé (periodicite nulle), non suspendues, hors
        //    démonstration, essai pas encore terminé.
        List<Essai> essais = jdbc.query("SELECT f.id, f.unique_id, "
                + "COALESCE(NULLIF(f.nom, ''), (SELECT u2.farm_name FROM utilisateurs u2 WHERE u2.farm_id = f.id "
                + "  AND u2.farm_name IS NOT NULL ORDER BY u2.id LIMIT 1), 'votre ferme'), "
                + "(SELECT MIN(u.created_at) FROM utilisateurs u WHERE u.farm_id = f.id), a.date_debut, a.date_fin "
                + "FROM farms f JOIN abonnements a ON a.farm_id = f.id "
                + "WHERE a.periodicite IS NULL AND COALESCE(a.suspendu, false) = false "
                + "AND COALESCE(f.exclure_statistiques, false) = false AND a.date_fin >= ? "
                + "AND NOT EXISTS (SELECT 1 FROM paiements_abonnement p WHERE p.abonnement_id = a.id AND p.statut = 'VALIDE')",
                (rs, i) -> {
                    java.sql.Timestamp t = rs.getTimestamp(4);
                    LocalDate inscription = t != null ? t.toLocalDateTime().toLocalDate() : rs.getDate(5).toLocalDate();
                    return new Essai(rs.getLong(1), rs.getString(2), rs.getString(3), inscription, rs.getDate(6).toLocalDate());
                }, auj);
        if (essais.isEmpty()) return List.of();

        // 2. Étapes du guide (projet, saisie) de toutes les fermes : une requête.
        Map<Long, GuideDemarrageService.Etapes> etapes = guide.etapes(null);
        // 3. E-mails d'essai déjà envoyés.
        Set<String> deja = new HashSet<>();
        jdbc.query("SELECT farm_id, type FROM envois_automatiques WHERE type LIKE 'ESSAI_%'",
                rs -> { deja.add(rs.getLong(1) + ":" + rs.getString(2)); });

        List<Candidat> res = new ArrayList<>();
        List<Object[]> retenus = new ArrayList<>();
        for (Essai e : essais) {
            GuideDemarrageService.Etapes et = etapes.getOrDefault(e.farmId(), GuideDemarrageService.Etapes.vide());
            if (et.saisie()) continue; // ferme déjà active
            int jours = (int) ChronoUnit.DAYS.between(e.inscription(), auj);
            long restants = ChronoUnit.DAYS.between(auj, e.dateFin());
            String type = null;
            if (jours >= 1 && jours <= 2 && !et.projet()) type = J1;
            else if (jours >= 3 && jours <= 6) type = J3;
            else if (jours >= 7 && restants >= 0) type = J7;
            if (type == null || deja.contains(e.farmId() + ":" + type)) continue;
            retenus.add(new Object[] { e, type, jours, restants });
        }
        if (retenus.isEmpty()) return List.of();

        // 4. Destinataires : ADMIN actifs avec un e-mail, toutes les fermes retenues en une requête.
        Map<Long, List<DestinatairesAdmin.Destinataire>> admins = destinataires.parFerme(retenus.stream().map(r -> ((Essai) r[0]).farmId()).toList());
        for (Object[] r : retenus) {
            Essai e = (Essai) r[0];
            String type = (String) r[1];
            int jours = (int) r[2];
            long restants = (long) r[3];
            List<Destinataire> dest = admins.getOrDefault(e.farmId(), List.of()).stream()
                    .map(d -> new Destinataire(d.nom(), d.email())).toList();
            if (dest.isEmpty()) continue;
            res.add(new Candidat(e.farmId(), e.uid(), e.nom(), type, jours, sujet(type, restants),
                    message(type, e.nom(), e.dateFin(), restants), dest));
        }
        return res;
    }

    static String dans(long jours) {
        if (jours <= 0) return "aujourd'hui";
        if (jours == 1) return "demain";
        return "dans " + jours + " jours";
    }

    static String sujet(String type, long restants) {
        return switch (type) {
            case J1 -> "Bien démarrer avec Cocorico en 3 étapes";
            case J3 -> "Besoin d'aide pour votre première saisie ?";
            default -> "Votre essai Cocorico se termine " + dans(restants) + ", on vous aide ?";
        };
    }

    static String message(String type, String ferme, LocalDate dateFin, long restants) {
        return switch (type) {
            case J1 -> "Votre ferme " + ferme + " est prête sur Cocorico. Pour commencer, il suffit de 3 étapes simples :\n\n"
                    + "1. Créez un site et un poulailler.\n"
                    + "2. Créez votre premier Projet : la bande de sujets, avec la date d'arrivée et le nombre de sujets.\n"
                    + "3. Faites une première saisie : collecte d'œufs, aliment donné ou mortalité.\n\n"
                    + "Le guide « Bien démarrer » sur votre tableau de bord vous montre chaque étape.\n\n"
                    + "Besoin d'aide ? Écrivez-nous sur WhatsApp au " + WHATSAPP + ".";
            case J3 -> "La ferme " + ferme + " n'a pas encore de saisie dans Cocorico. Une saisie prend moins d'une minute : "
                    + "la collecte d'œufs du jour, l'aliment donné ou une mortalité.\n\n"
                    + "Vous pouvez saisir sur l'application web ou sur l'application mobile, même sans internet.\n\n"
                    + "Nous pouvons vous aider à tout mettre en place. Écrivez-nous sur WhatsApp au " + WHATSAPP
                    + ", nous répondons vite.";
            default -> "La période d'essai de la ferme " + ferme + " se termine le " + AbonnementEcheance.date(dateFin)
                    + " (" + dans(restants) + "). Vous n'avez pas encore fait de saisie.\n\n"
                    + "Pour profiter de l'essai : créez votre Projet, puis saisissez les collectes, l'aliment et la mortalité. "
                    + "Vous verrez tout de suite vos chiffres : taux de ponte, stock, ventes.\n\n"
                    + "On vous aide ? Écrivez-nous sur WhatsApp au " + WHATSAPP + ". Nous pouvons tout installer avec vous.";
        };
    }
}
