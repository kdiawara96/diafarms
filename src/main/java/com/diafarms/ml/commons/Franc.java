package com.diafarms.ml.commons;

// Règle unique d'arrondi des MONTANTS (FCFA) : tout montant reçu d'un client (web ou
// mobile) ou calculé par le serveur (quantité x prix, poids x prix/kg, taux x jours)
// est arrondi au franc entier avant d'être enregistré. Les PRIX UNITAIRES gardent
// leurs décimales (ex. 62,5 F l'œuf) ; seul le montant total est arrondi.
// Math.round : 0,5 arrondi au-dessus (93 499,5 -> 93 500), comme Math.round du mobile.
public final class Franc {

    private Franc() {}

    public static Double arrondi(Double montant) {
        return montant == null ? null : (double) Math.round(montant);
    }

    public static double arrondi(double montant) {
        return (double) Math.round(montant);
    }

    /** Modification : un montant envoyé qui vaut, au franc près, le montant actuel n'est
     * PAS un changement (null = inchangé). Une ancienne vente à centimes reste donc
     * intacte quand le formulaire renvoie son montant arrondi avec une autre
     * correction (date...), sans toucher au verrou de facture ni aux imputations. */
    public static Double modifie(Double envoye, Double actuel) {
        if (envoye == null) return null;
        if (actuel != null && Math.round(envoye) == Math.round(actuel)) return null;
        return arrondi(envoye);
    }

    /** Reste à payer AFFICHÉ (statut, listes, comptes) : un reste de moins d'un
     * demi-franc (centimes d'une ancienne vente, impossibles à payer en FCFA) vaut 0.
     * L'imputation garde, elle, la précision au centime. */
    public static double solde(double reste) {
        return reste < 0.5 ? 0.0 : reste;
    }

    /** Une vente est soldée quand son reste à payer est sous le demi-franc. */
    public static boolean estSolde(double reste) {
        return reste < 0.5;
    }
}
