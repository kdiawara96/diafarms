package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Statistiques des ventes de réformes sur une période (GET /ventes-reforme/stats) —
// voir VenteReformeImpl.stats. Toujours en SUJETS pour les quantités ; le poids ne
// concerne que les ventes au kilo (typeVente=KILO). Les moyennes sont null quand le
// dénominateur est nul (aucune vente, aucune vente au kilo).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StatsReformeDTO {
    private LocalDate dateDebut;
    private LocalDate dateFin;
    private String projetUniqueId; // null = toute la ferme
    private String projetCode;
    private Chiffres total;
    // Détail par projet contributeur (parts de répartition) ; filtré sur le projet
    // demandé s'il y en a un.
    private List<Chiffres> parProjet;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class Chiffres {
        private String projetUniqueId; // null pour le total
        private String projetCode;
        private Integer nombreVentes;
        private Integer nombreSujetsVendus; // toutes ventes (TETE + KILO)
        private Double montantTotal; // toutes ventes
        private Double prixMoyenParTete; // montantTotal / nombreSujetsVendus
        private Integer nombreSujetsVendusAuKilo;
        private Double poidsTotalVenduKg; // ventes KILO seulement
        private Double montantVenduAuKilo;
        private Double prixMoyenKg; // montantVenduAuKilo / poidsTotalVenduKg
        private Double poidsMoyenParSujetKg; // poidsTotalVenduKg / nombreSujetsVendusAuKilo
    }
}
