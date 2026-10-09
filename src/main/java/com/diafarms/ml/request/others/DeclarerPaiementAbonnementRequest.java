package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class DeclarerPaiementAbonnementRequest {
    private String periodicite; // "MENSUEL" ou "ANNUEL"
    private String moyenPaiement; // "Orange Money", "Wave", "Virement", "Espèces"...
    private String reference; // optionnel
    // Montant que la page affichait (prix par poule). Facultatif (anciens clients) : s'il
    // diffère du tarif actuel du serveur, la déclaration est refusée pour que la ferme
    // revoie le prix avant de déclarer.
    private Double montantAffiche;
    // Crédit prépayé : montant rechargé (« J'ai rechargé »), choisi par la ferme. Absent
    // (ancien client) : le prix du mois ou de l'an affiché devient le montant rechargé.
    private Double montant;
}
