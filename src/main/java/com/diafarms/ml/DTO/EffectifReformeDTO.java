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
public class EffectifReformeDTO {
    private Integer nbSujetsInitial;
    private Integer mortaliteCumulee;
    private Integer sujetsReformesCumulee;
    private Integer effectifVivant;
}
