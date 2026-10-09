package com.diafarms.ml.DTO;

import java.time.LocalDate;

// Crédit prépayé d'une ferme (voir CreditService.etat et commons/AbonnementCredit).
// Toujours en FCFA. Ajouté à AbonnementDTO (champ credit) et à la fiche de la console.
public record CreditDTO(
        // false : ferme encore dans l'ancien modèle (pas encore convertie).
        boolean actif,
        // Crédit restant (négatif = somme due).
        double solde,
        double dette,
        // Crédit à zéro ou en dessous, et plus en essai : il faut recharger.
        boolean aRecharger,
        // Crédit positif mais moins d'environ 30 jours estimés.
        boolean creditBas,
        // « environ 5 mois » au rythme actuel (null : plus de 10 ans).
        Double moisRestants,
        String phraseMois,
        // Dernier jour couvert (= Abonnement.dateFin), estimation tant que le crédit est positif.
        LocalDate finEstimee,
        // Ce mois-ci : moyenne des poules vivantes du 1er (ou du premier jour payé) à
        // aujourd'hui, nombre de jours comptés, mensualité prévue à la fin du mois.
        double moyennePoulesMois,
        int joursComptesMois,
        double mensualitePrevue,
        // Coût d'un mois entier au rythme actuel (estimations, revenu de la console).
        double coutMensuel,
        boolean comptageEnErreur,
        // Premier jour payé par le crédit (lendemain de l'essai ou de la période déjà payée).
        LocalDate creditDepuis,
        // true : le crédit n'a pas encore commencé (essai ou période déjà payée en cours).
        boolean avantCredit,
        boolean essaiRefuse,
        boolean prixFixe,
        // Réglages utiles à la page : bonus, sur devis.
        double bonusSeuil,
        double bonusPourcent,
        int seuilSurDevis,
        // Console : ferme au-dessus du seuil « sur devis » sans tarif spécial.
        boolean aChiffrer,
        // Ferme d'avant le crédit dans (ou juste après) sa période déjà payée, sans recharge :
        // jamais de texte d'essai ni de « crédit épuisé ».
        boolean periodePayee,
        // Minimum par mois (réglage), pour l'affichage.
        double prixMinimumMensuel) {
}
