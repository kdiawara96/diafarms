package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Taux effectivement en vigueur pour UNE période donnée — peut différer du taux
// ACTUEL de la grille si celle-ci a changé depuis (voir
// SalaireServiceImpl.resolveTauxPourPeriode).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class TauxSalaireDTO {
    private String modePaiement;
    private Double tauxBase;
}
