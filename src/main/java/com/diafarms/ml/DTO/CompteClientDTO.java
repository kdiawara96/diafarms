package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.util.List;

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
    private double avance;            // totalPaye − toutes imputations actives (= avanceLibre + avanceReservee)
    private double avanceLibre;       // part de l'avance utilisable pour n'importe quelle vente ou un remboursement
    private double avanceReservee;    // part réservée aux commandes en cours (acomptes pas encore livrés)
    private List<AvanceReserveeDTO> avancesReservees; // détail par commande
    private double solde;             // resteAPayer − avance (positif = doit)

    @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Builder
    public static class AvanceReserveeDTO {
        private String commandeUniqueId;
        private LocalDate dateCommande;
        private double montant;
    }
}
