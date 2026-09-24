package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Un point de la courbe de poids d'un projet : une session de pesée terminée.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class EvolutionPoidsDTO {
    private String sessionUniqueId;
    private LocalDateTime date;        // dateFin de la session
    private Double poidsMoyenKg;
    private Integer nombreTotalSujets;
}
