package com.diafarms.ml.DTO;


import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestissementStatsDTO {
    private Double totalBrut;       // Somme globale de tous les montants d'achats
    private Double totalValeurNette; // Somme globale de toutes les valeurs nettes courantes
    private Long totalActifs;        // Nombre total d'investissements (lignes)
}