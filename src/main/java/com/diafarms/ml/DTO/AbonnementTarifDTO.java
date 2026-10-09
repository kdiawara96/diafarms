package com.diafarms.ml.DTO;

import java.time.LocalDate;

// Tarif d'abonnement d'une ferme (ou d'une simulation), calculé par
// AbonnementTarifService (voir commons/AbonnementTarif pour la règle). Toujours en FCFA,
// jamais dans la devise de la ferme.
public record AbonnementTarifDTO(
        // Poules comptées : le plus grand nombre de sujets vivants des Projets en cours de la
        // ferme sur les 30 derniers jours (aujourd'hui compris). 0 sans Projet.
        int poulesComptees,
        // Jour où ce maximum a été atteint (le plus récent en cas d'égalité), null sans sujet.
        LocalDate dateMax,
        int fenetreJours,
        // Prix à payer pour un mois et pour un an (prix fixe s'il y en a un).
        double prixMensuel,
        double prixAnnuel,
        // Détail de la règle par poule : poules × prix par poule, puis arrondi au-dessus,
        // puis minimum. prixMensuelSelonPoules = ce que donnerait la règle sans prix fixe.
        double montantParPoules,
        double montantArrondi,
        boolean minimumApplique,
        double prixMensuelSelonPoules,
        // Prix fixe (tarif spécial décidé par l'équipe, Abonnement.prixMensuelFixe) : il
        // remplace la règle par poule.
        boolean prixFixe,
        Double prixMensuelFixe,
        String motifPrixFixe,
        // Réglages utilisés (AbonnementConfig, valeurs par défaut si vides).
        double prixParPoule,
        double prixMinimumMensuel,
        int moisOffertsAnnuel,
        int arrondi,
        // true : le comptage des poules a échoué, le prix minimum est appliqué par sécurité.
        boolean calculEnErreur,
        // Crédit prépayé : au-delà de seuilSurDevis poules, tarif sur devis (la simulation
        // n'annonce plus de prix ; une ferme au-dessus sans tarif spécial est « à chiffrer »
        // dans la console, sa mensualité suit la règle en attendant).
        boolean surDevis,
        int seuilSurDevis) {
}
