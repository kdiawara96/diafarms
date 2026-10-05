package com.diafarms.ml.commons;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;

// Calcul unique de l'échéance d'un abonnement (ou d'un essai), partagé par
// AbonnementServiceImpl (statut effectif, page Abonnement, portail SUPER_ADMIN),
// AbonnementRappelService (rappels J-7 / J-1 / début de grâce) et
// NotificationServiceImpl (cloche). Aucune dépendance à l'utilisateur courant ni à la
// devise : utilisable depuis la tâche planifiée, sans requête HTTP.
//
// Règles (dates seules, pas d'heure) :
//   - dateFin est incluse : la ferme est active toute la journée dateFin ;
//   - délai de grâce de G jours : du lendemain de dateFin à dateFin + G inclus, tout
//     reste ouvert (rien n'est bloqué, ni web ni mobile) ;
//   - à partir de dateFin + G + 1 : EXPIRE (le web bloque, comme avant).
// Le serveur ne bloque JAMAIS l'API elle-même (ni avant, ni maintenant) : seul le web
// (AbonnementGate) lit statutEffectif=EXPIRE pour afficher l'écran de blocage.
public final class AbonnementEcheance {

    public static final int DELAI_GRACE_JOURS_DEFAUT = 5;

    public static final String RAPPEL_J7 = "J7";
    public static final String RAPPEL_J1 = "J1";
    public static final String RAPPEL_GRACE = "GRACE";

    private static final DateTimeFormatter FORMAT_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private AbonnementEcheance() {}

    public static int delaiGraceJours(AbonnementConfig config) {
        Integer d = config != null ? config.getDelaiGraceJours() : null;
        return d == null || d < 0 ? DELAI_GRACE_JOURS_DEFAUT : d;
    }

    public record Etat(
            boolean estEssai,
            LocalDate dateFin,
            int delaiGraceJours,
            // dateFin - aujourd'hui : 0 le dernier jour, négatif après.
            long joursRestants,
            boolean enGrace,
            boolean expire,
            // Dernier jour d'accès (dateFin + délai de grâce).
            LocalDate dernierJourAcces,
            // Jours d'accès qu'il reste pendant la grâce, aujourd'hui compris (0 hors grâce).
            long joursGraceRestants,
            // Suspension manuelle par le SUPER_ADMIN (Abonnement.suspendu) : bloque le web
            // comme une expiration, quelle que soit la date de fin.
            boolean suspendu) {

        // Statut exposé dans statutEffectif (/abonnements/moi, AbonnementGate) : une ferme
        // suspendue répond EXPIRE pour que tout client, même ancien, la bloque ; le web
        // récent lit en plus AbonnementDTO.suspendu pour afficher le bon message.
        public String statut() {
            if (expire || suspendu) return "EXPIRE";
            return estEssai ? "ESSAI" : "ACTIF";
        }

        // Web bloqué (expiré après la grâce, ou suspendu).
        public boolean bloque() {
            return expire || suspendu;
        }

        // Statut détaillé pour la console SUPER_ADMIN : SUSPENDU, EXPIRE, GRACE, ESSAI ou ACTIF.
        public String statutDetaille() {
            if (suspendu) return "SUSPENDU";
            if (expire) return "EXPIRE";
            if (enGrace) return "GRACE";
            return estEssai ? "ESSAI" : "ACTIF";
        }

        // Rappel qui correspond à aujourd'hui, ou null. Fenêtres (et non un jour exact)
        // pour rattraper un jour où la tâche n'aurait pas tourné (serveur arrêté) :
        // J-7 de J-7 à J-2, J-1 de J-1 au dernier jour, grâce pendant toute la grâce.
        // L'unicité « une fois par période » est assurée par AbonnementRappel.
        public String rappelDuJour() {
            if (expire || suspendu) return null; // jamais de rappel à une ferme suspendue
            if (enGrace) return RAPPEL_GRACE;
            if (joursRestants >= 2 && joursRestants <= 7) return RAPPEL_J7;
            if (joursRestants >= 0 && joursRestants <= 1) return RAPPEL_J1;
            return null;
        }
    }

    public static Etat calculer(Abonnement abonnement, AbonnementConfig config, LocalDate aujourdHui) {
        int grace = delaiGraceJours(config);
        LocalDate dateFin = abonnement.getDateFin();
        long joursRestants = ChronoUnit.DAYS.between(aujourdHui, dateFin);
        LocalDate dernierJour = dateFin.plusDays(grace);
        boolean enGrace = joursRestants < 0 && !aujourdHui.isAfter(dernierJour);
        boolean expire = aujourdHui.isAfter(dernierJour);
        long joursGraceRestants = enGrace ? ChronoUnit.DAYS.between(aujourdHui, dernierJour) + 1 : 0;
        boolean estEssai = abonnement.getPeriodicite() == null;
        return new Etat(estEssai, dateFin, grace, joursRestants, enGrace, expire, dernierJour, joursGraceRestants,
                abonnement.estSuspendu());
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(FORMAT_DATE);
    }

    // Prix de l'abonnement : toujours en FCFA (jamais la devise de la ferme).
    public static String fcfa(Double montant) {
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.FRANCE);
        // Espace normale (et non l'espace fine insécable du format français), lisible
        // partout, y compris dans les emails en texte brut.
        return nf.format(montant == null ? 0 : Math.round(montant)).replace(' ', ' ').replace(' ', ' ') + " FCFA";
    }

    private static String jours(long n) {
        return n + (n > 1 ? " jours" : " jour");
    }

    // Phrase courte (cloche, première ligne de l'email), toujours recalculée sur l'état
    // du jour pour rester juste même si le rappel a été envoyé la veille.
    public static String messageCourt(Etat e) {
        String sujet = e.estEssai() ? "Votre période d'essai" : "Votre abonnement";
        if (e.enGrace()) {
            return sujet + " est terminé" + (e.estEssai() ? "e" : "") + " depuis le " + date(e.dateFin())
                    + ". Il vous reste " + jours(e.joursGraceRestants())
                    + " pour renouveler avant que l'accès soit bloqué.";
        }
        if (e.joursRestants() == 0) {
            return sujet + " se termine aujourd'hui (" + date(e.dateFin()) + ").";
        }
        if (e.joursRestants() == 1) {
            return sujet + " se termine demain (" + date(e.dateFin()) + ").";
        }
        return sujet + " se termine le " + date(e.dateFin()) + " (dans " + jours(e.joursRestants()) + ").";
    }

    public static String sujetEmail(Etat e) {
        if (e.enGrace()) {
            return (e.estEssai() ? "Votre période d'essai Cocorico est terminée" : "Votre abonnement Cocorico est terminé")
                    + " : il vous reste " + jours(e.joursGraceRestants());
        }
        return (e.estEssai() ? "Votre période d'essai Cocorico se termine le " : "Votre abonnement Cocorico se termine le ")
                + date(e.dateFin());
    }

    // Corps complet (email) : quand, combien, comment renouveler. Pas de paiement en
    // ligne pour l'instant : on déclare le paiement sur la page Abonnement (« J'ai
    // payé »), il est ensuite validé par l'équipe.
    public static String messageComplet(Etat e, String farmNom, AbonnementConfig config, boolean paiementEnAttente) {
        StringBuilder sb = new StringBuilder();
        String sujet = e.estEssai() ? "La période d'essai de la ferme " + farmNom
                : "L'abonnement de la ferme " + farmNom;
        if (e.enGrace()) {
            sb.append(sujet).append(" est terminé").append(e.estEssai() ? "e" : "").append(" depuis le ")
              .append(date(e.dateFin())).append(". Il vous reste ").append(jours(e.joursGraceRestants()))
              .append(" pour renouveler avant que l'accès soit bloqué.");
        } else if (e.joursRestants() == 0) {
            sb.append(sujet).append(" se termine aujourd'hui (").append(date(e.dateFin())).append(").");
        } else if (e.joursRestants() == 1) {
            sb.append(sujet).append(" se termine demain (").append(date(e.dateFin())).append(").");
        } else {
            sb.append(sujet).append(" se termine le ").append(date(e.dateFin()))
              .append(" (dans ").append(jours(e.joursRestants())).append(").");
        }
        sb.append("\n\n");
        if (paiementEnAttente) {
            sb.append("Vous avez déjà déclaré un paiement : il est en cours de vérification. "
                    + "Vous n'avez rien d'autre à faire.\n\n");
        } else {
            if (config != null && config.getPrixMensuel() != null && config.getPrixAnnuel() != null) {
                sb.append("Prix : ").append(fcfa(config.getPrixMensuel())).append(" par mois, ou ")
                  .append(fcfa(config.getPrixAnnuel())).append(" par an.\n\n");
            }
            sb.append("Pour renouveler : envoyez le montant par mobile money au +223 83 91 86 99, puis ouvrez la page "
                    + "Abonnement dans Cocorico et cliquez sur « J'ai payé ». Votre paiement sera vérifié puis validé.\n"
                    + "Une question ? Écrivez-nous sur WhatsApp au +223 83 91 86 99.\n\n");
        }
        if (!e.enGrace() && e.delaiGraceJours() > 0) {
            sb.append("Après cette date, vous aurez encore ").append(jours(e.delaiGraceJours()))
              .append(" pour renouveler avant que l'accès soit bloqué.");
        } else if (!e.enGrace()) {
            sb.append("Après cette date, l'accès sera bloqué jusqu'au renouvellement.");
        } else {
            sb.append("Dernier jour d'accès : le ").append(date(e.dernierJourAcces())).append(".");
        }
        return sb.toString();
    }
}
