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
}
