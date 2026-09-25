package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

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
public class PeseeDTO {
    private String uniqueId;
    private Integer nombreSujets;
    private Double poidsKg;
    private LocalDateTime dateHeure;
    private Boolean annulee;
    private String creeParNom;
    private String origine;   // MOBILE | WEB
    private Boolean modifiee; // corrigée depuis le web
}
