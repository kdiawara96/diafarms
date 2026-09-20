package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Ce qu'une saisie de production a le droit de ne pas dépasser, pour les alertes en
// direct (web + mobile) : voir EffectifVivantHelper et PlafondSaisieControllers.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class PlafondSaisieDTO {
    // Effectif vivant du périmètre (bâtiment s'il est connu, sinon projet entier).
    private Integer effectifVivant;
    // "BATIMENT" ou "PROJET".
    private String perimetre;
    // Œufs déjà collectés à la date demandée dans ce périmètre.
    private Integer oeufsDejaCollectes;
    // Ce qu'on peut encore collecter ce jour-là : max(0, effectifVivant - déjà collectés).
    private Integer oeufsRestants;
}
