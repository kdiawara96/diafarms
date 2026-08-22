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
public class StockOeufsDTO {
    private Integer totalCollecte;
    private Integer totalCasse;
    private Integer totalNonUtilisable;
    private Integer totalVendu;
    private Integer stockRestant;
    private String statut; // "ACTIF" | "EPUISE"
}
