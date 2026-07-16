package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Stock de sujets réformés vendables, à l'échelle de la ferme entière — distinct de
// EffectifReformeDTO (effectif vivant d'UN projet, côté Production/ReformeImpl).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StockReformeDTO {
    private Integer totalReforme;
    private Integer totalVendu;
    private Integer stockRestant;
    private String statut; // "ACTIF" | "EPUISE"
}
