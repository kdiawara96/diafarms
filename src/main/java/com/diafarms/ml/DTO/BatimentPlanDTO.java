package com.diafarms.ml.DTO;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Vue plan des poulaillers (GET /batiments/plan) : un poulailler de la ferme avec ce qui
// sert à le dessiner (capacité, effectif vivant, projet(s) présent(s)). Calculé en un
// nombre fixe de requêtes, quel que soit le nombre de poulaillers (voir BatimentImpl.plan).
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatimentPlanDTO {
    private String uniqueId;
    private String nom;
    private Integer capacite;
    private String statut; // DISPONIBLE | OCCUPE | MAINTENANCE (champ du poulailler)
    private Double superficieM2;
    private boolean occupe; // occupation active (calculée sur les dates, pas sur statut)
    // Sujets placés (occupations actives) moins mortalité et réforme attribuées au
    // poulailler ; null si aucune occupation active ou nombre de sujets non renseigné.
    private Integer effectifVivant;
    private Integer sujetsPlaces;
    private List<Occupant> projets;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Occupant {
        private String uniqueId;
        private String code;
        private String titre;
        private Integer nbSujets;
        private String dateEntree;
        // Site du projet (facultatif) : sert à la Vue plan des Sites.
        private String siteUniqueId;
        private String siteNom;
    }
}
