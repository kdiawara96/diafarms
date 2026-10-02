package com.diafarms.ml.commons;

// Règle unique d'arrondi des MONTANTS : tout montant reçu d'un client (web ou
// mobile) ou calculé par le serveur (quantité x prix, poids x prix/kg, taux x jours)
// est arrondi à l'unité de la devise de la ferme avant d'être enregistré : au franc
// entier pour XOF/XAF/GNF (0 décimale, comportement historique inchangé), au centime
// pour EUR/USD/NGN/GHS... (voir Devise, devise courante = ferme de l'utilisateur). Les PRIX UNITAIRES gardent
// leurs décimales (ex. 62,5 F l'œuf) ; seul le montant total est arrondi.
// Math.round : 0,5 arrondi au-dessus (93 499,5 -> 93 500), comme Math.round du mobile.
public final class Franc {

    private Franc() {}

    public static Double arrondi(Double montant) {
        return montant == null ? null : Devise.arrondi(montant, Devise.decimales());
    }

    public static double arrondi(double montant) {
        return Devise.arrondi(montant, Devise.decimales());
    }

    /** Demi-unité de la plus petite subdivision de la devise : 0,5 (franc), 0,005 (centime). */
    private static double demiUnite() {
        return 0.5 / Math.pow(10, Devise.decimales());
    }

    /** Modification : un montant envoyé qui vaut, au franc près, le montant actuel n'est
     * PAS un changement (null = inchangé). Une ancienne vente à centimes reste donc
     * intacte quand le formulaire renvoie son montant arrondi avec une autre
     * correction (date...), sans toucher au verrou de facture ni aux imputations. */
    public static Double modifie(Double envoye, Double actuel) {
        if (envoye == null) return null;
        int d = Devise.decimales();
        if (actuel != null && Devise.arrondi(envoye, d) == Devise.arrondi(actuel, d)) return null;
        return arrondi(envoye);
    }

    /** Reste à payer AFFICHÉ (statut, listes, comptes) : un reste de moins d'un
     * demi-franc (centimes d'une ancienne vente, impossibles à payer en FCFA) vaut 0.
     * L'imputation garde, elle, la précision au centime. */
    public static double solde(double reste) {
        return reste < demiUnite() ? 0.0 : reste;
    }

    /** Une vente est soldée quand son reste à payer est sous le demi-franc. */
    public static boolean estSolde(double reste) {
        return reste < demiUnite();
    }
}
