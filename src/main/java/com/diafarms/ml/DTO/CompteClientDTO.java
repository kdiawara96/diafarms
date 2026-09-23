package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// État du compte d'un client, entièrement recalculé (voir CompteClientService).
@Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
public class CompteClientDTO {
    private String clientUniqueId;
    private String clientNom;
    private double totalVendu;        // Σ ventes actives (œufs + réforme)
    private double totalPaye;         // Σ paiements actifs
    private double totalRembourse;    // Σ remboursements actifs
    private double totalImputeVentes; // Σ imputations actives sur des ventes
    private double resteAPayer;       // totalVendu − totalImputeVentes
    private double avance;            // totalPaye − toutes imputations actives
    private double solde;             // resteAPayer − avance (positif = doit)
}
