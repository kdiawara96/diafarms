package com.diafarms.ml.commons;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

import com.diafarms.ml.models.AbonnementConfig;

// Règles du crédit prépayé (décision du propriétaire, 2026-10-09), sans base de données
// ni utilisateur courant : utilisables partout, y compris dans les tâches planifiées.
// Le service (CreditService) lit et écrit ; ici on ne fait que calculer.
//
// 1. Recharge : la ferme envoie l'argent puis déclare « J'ai rechargé » ; validée, la
//    somme est ajoutée à son crédit. Une recharge d'au moins bonusSeuil (50 000 FCFA)
//    reçoit en plus bonusPourcent (20 %) de crédit offert.
// 2. Mensualité : à la fin de chaque mois, le crédit baisse de
//      prix par poule (6 FCFA) × moyenne des poules vivantes sur les jours du mois,
//      arrondi au-dessus à 100 FCFA, minimum 5 000 FCFA ;
//    pour un mois payé en partie seulement (premier mois payant), la moyenne est prise
//    sur les jours payés, et le prix comme le minimum sont ramenés à ces jours :
//    montant = plus grand de (6 × moyenne, 5 000) × jours payés / jours du mois, arrondi.
//    Le tarif spécial (prix fixe par mois) remplace ce calcul (lui aussi au prorata).
// 3. Accès : un mois commencé avec un crédit positif est couvert (même si la mensualité
//    fait passer le crédit sous zéro). Crédit à zéro ou en dessous après une mensualité :
//    la ferme est « à recharger », la grâce commence le 1er du mois suivant, puis le
//    blocage habituel. Abonnement.dateFin = dernier jour couvert (estimation tant que le
//    crédit est positif) : rappels J-7 / J-1, grâce, blocage web et mobile inchangés.
// 4. Une recharge rembourse d'abord ce qui est dû (le crédit négatif), puis ajoute.
public final class AbonnementCredit {

    public static final double BONUS_SEUIL_DEFAUT = 50_000;
    public static final double BONUS_POURCENT_DEFAUT = 20;
    public static final int SEUIL_SUR_DEVIS_DEFAUT = 10_000;
    public static final double CREDIT_PARRAINAGE_DEFAUT = 5_000;
    // « Crédit bas » : moins d'environ 30 jours d'accès estimés.
    public static final int CREDIT_BAS_JOURS = 30;
    // Estimation plafonnée à 10 ans (crédit énorme ou coût nul).
    public static final int MOIS_MAX = 120;

    private AbonnementCredit() {}

    public record Regles(double bonusSeuil, double bonusPourcent, int seuilSurDevis, double creditParrainage) {}

    public static Regles regles(AbonnementConfig c) {
        Double s = c != null ? c.getBonusSeuil() : null;
        Double p = c != null ? c.getBonusPourcent() : null;
        Integer d = c != null ? c.getSeuilSurDevis() : null;
        Double r = c != null ? c.getCreditParrainage() : null;
        return new Regles(
                s == null || s < 0 ? BONUS_SEUIL_DEFAUT : s,
                p == null || p < 0 ? BONUS_POURCENT_DEFAUT : p,
                d == null || d < 1 ? SEUIL_SUR_DEVIS_DEFAUT : d,
                r == null || r < 0 ? CREDIT_PARRAINAGE_DEFAUT : r);
    }

    // Bonus d'une recharge (arrondi au franc). 0 sous le seuil.
    public static double bonus(double montant, Regles r) {
        if (montant <= 0 || r.bonusPourcent() <= 0 || montant < r.bonusSeuil()) return 0;
        return Math.round(montant * r.bonusPourcent() / 100.0);
    }

    // Détail d'une mensualité (ou d'une estimation).
    public record Mensualite(double montant, double poulesMoyenne, int jours, int joursMois, boolean prixFixe,
            boolean minimumApplique) {}

    // moyenne : poules vivantes en moyenne sur les jours payés ; jours payés sur joursMois.
    // prixFixe : tarif spécial par mois (null ou <= 0 = règle par poule).
    public static Mensualite mensualite(double moyenne, int jours, int joursMois, AbonnementTarif.Regles t,
            Double prixFixe) {
        int jm = Math.max(1, joursMois);
        int j = Math.max(0, Math.min(jours, jm));
        double fraction = (double) j / jm;
        double moy = Math.max(0, moyenne);
        if (prixFixe != null && prixFixe > 0) {
            return new Mensualite(AbonnementTarif.arrondirAuDessus(prixFixe * fraction, t.arrondi()), moy, j, jm, true,
                    false);
        }
        double selonPoules = t.prixParPoule() * moy * fraction;
        double minimum = t.prixMinimumMensuel() * fraction;
        boolean min = selonPoules < minimum;
        double brut = Math.max(selonPoules, minimum);
        return new Mensualite(AbonnementTarif.arrondirAuDessus(brut, t.arrondi()), moy, j, jm, false, min);
    }

    // Coût d'un mois entier au rythme d'une moyenne de poules (estimations).
    public static double coutMensuel(double moyenne, AbonnementTarif.Regles t, Double prixFixe) {
        return mensualite(moyenne, 30, 30, t, prixFixe).montant();
    }

    // Jours payés d'un mois : du plus tard entre le 1er et creditDepuis, à la fin du mois ;
    // jamais à partir de creditEpuiseLe (crédit à zéro : jours non facturés, la ferme est en
    // grâce puis bloquée). null si aucun jour.
    public record Periode(LocalDate du, LocalDate au) {
        public int jours() {
            return (int) ChronoUnit.DAYS.between(du, au) + 1;
        }
    }

    public static Periode periodePayee(YearMonth mois, LocalDate creditDepuis, LocalDate creditEpuiseLe) {
        if (creditDepuis == null) return null;
        LocalDate du = mois.atDay(1).isBefore(creditDepuis) ? creditDepuis : mois.atDay(1);
        LocalDate au = mois.atEndOfMonth();
        if (creditEpuiseLe != null && !creditEpuiseLe.isAfter(au)) au = creditEpuiseLe.minusDays(1);
        if (au.isBefore(du)) return null;
        return new Periode(du, au);
    }

    // Dernier jour couvert par un crédit POSITIF, au rythme coutMensuel par mois : chaque
    // mois qui commence avec un crédit positif est couvert. Le mois en cours compte en
    // entier (sa mensualité tombe à la fin du mois), au prorata s'il commence après le 1er
    // (premier mois payant).
    public static LocalDate finEstimee(double solde, LocalDate aujourdHui, LocalDate creditDepuis, double coutMensuel) {
        LocalDate depart = creditDepuis != null && creditDepuis.isAfter(aujourdHui) ? creditDepuis : aujourdHui;
        if (coutMensuel <= 0) return depart.plusYears(10);
        YearMonth m = YearMonth.from(depart);
        double reste = solde;
        YearMonth dernier = null;
        for (int i = 0; i < MOIS_MAX && reste > 0; i++) {
            double fraction = 1.0;
            if (creditDepuis != null && creditDepuis.isAfter(m.atDay(1)) && YearMonth.from(creditDepuis).equals(m)) {
                fraction = (double) (m.lengthOfMonth() - creditDepuis.getDayOfMonth() + 1) / m.lengthOfMonth();
            }
            reste -= coutMensuel * fraction;
            dernier = m;
            m = m.plusMonths(1);
        }
        if (dernier == null) return depart.minusDays(1);
        return dernier.atEndOfMonth();
    }

    // Nouvelle échéance après un mouvement ou chaque jour (CreditService.recalculer) :
    //   - crédit positif : creditEpuiseLe vide, dateFin = finEstimee (coutMensuel null =
    //     comptage en échec : on garde la fin actuelle, jamais avant la fin du mois couvert) ;
    //   - crédit à zéro ou en dessous : dateFin = veille du premier jour non couvert. Ce
    //     jour est déjà connu (mensualité qui a vidé le crédit, fin de l'essai) ; sinon
    //     (ajustement en débit) c'est le 1er du mois suivant : le mois en cours a commencé
    //     avec un crédit positif, il reste couvert.
    public record Fin(LocalDate creditEpuiseLe, LocalDate dateFin) {}

    public static Fin fin(double solde, LocalDate aujourdHui, LocalDate creditDepuis, LocalDate creditEpuiseLe,
            Double coutMensuel, LocalDate dateFinActuelle) {
        if (solde > 0) {
            LocalDate f;
            if (coutMensuel == null) {
                LocalDate depart = creditDepuis != null && creditDepuis.isAfter(aujourdHui) ? creditDepuis : aujourdHui;
                LocalDate finMois = YearMonth.from(depart).atEndOfMonth();
                f = dateFinActuelle != null && dateFinActuelle.isAfter(finMois) ? dateFinActuelle : finMois;
            } else {
                f = finEstimee(solde, aujourdHui, creditDepuis, coutMensuel);
            }
            return new Fin(null, f);
        }
        LocalDate e = creditEpuiseLe;
        if (e == null) {
            e = YearMonth.from(aujourdHui).plusMonths(1).atDay(1);
            if (creditDepuis != null && creditDepuis.isAfter(e)) e = creditDepuis;
        }
        return new Fin(e, e.minusDays(1));
    }

    // Accès web bloqué par le crédit (crédit à zéro ou moins, grâce passée) : une recharge
    // fait alors repartir le crédit d'aujourd'hui (les jours bloqués ne sont jamais payés,
    // comme avant : un renouvellement après la grâce partait d'aujourd'hui).
    public static boolean bloqueParCredit(double solde, LocalDate creditEpuiseLe, int delaiGraceJours,
            LocalDate aujourdHui) {
        return solde <= 0 && creditEpuiseLe != null
                && aujourdHui.isAfter(creditEpuiseLe.minusDays(1).plusDays(delaiGraceJours));
    }

    // « environ 5 mois » : solde / coût d'un mois, arrondi au demi-mois.
    public static Double moisRestants(double solde, double coutMensuel) {
        if (coutMensuel <= 0 || solde <= 0) return solde <= 0 ? 0.0 : null;
        return Math.round(solde / coutMensuel * 2) / 2.0;
    }

    public static String phraseMois(Double mois) {
        if (mois == null) return "plus de 10 ans";
        if (mois <= 0) return "crédit épuisé";
        if (mois < 1) return "moins d'un mois";
        String n = mois == Math.floor(mois) ? String.valueOf(mois.intValue())
                : String.valueOf(mois.intValue()) + " mois et demi";
        if (mois != Math.floor(mois)) return "environ " + n;
        return "environ " + n + " mois";
    }

    private static final String[] MOIS_FR = { "janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août",
            "septembre", "octobre", "novembre", "décembre" };

    // « septembre 2026 »
    public static String moisFr(YearMonth m) {
        return MOIS_FR[m.getMonthValue() - 1] + " " + m.getYear();
    }

    // « Septembre 2026 : 1 000 poules en moyenne sur 30 jours. »
    public static String libelleMensualite(YearMonth mois, Mensualite m) {
        String moisTxt = moisFr(mois);
        moisTxt = Character.toUpperCase(moisTxt.charAt(0)) + moisTxt.substring(1);
        String jours = m.jours() == m.joursMois() ? m.jours() + " jours"
                : m.jours() + " jour" + (m.jours() > 1 ? "s" : "") + " sur " + m.joursMois();
        if (m.prixFixe()) return moisTxt + " : tarif spécial, " + jours + ".";
        return moisTxt + " : " + AbonnementEcheance.nombre(Math.round(m.poulesMoyenne())) + " poules en moyenne sur "
                + jours + (m.minimumApplique() ? ", prix minimum." : ".");
    }
}
