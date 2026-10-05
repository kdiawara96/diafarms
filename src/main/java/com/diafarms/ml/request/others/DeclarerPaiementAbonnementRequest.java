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
}
