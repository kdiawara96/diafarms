package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StockAlimentDTO {
    private Double totalAchete;
    private Double totalConsomme;
    private Double stockRestant;
    private String statut; // "ACTIF" | "EPUISE"
}
