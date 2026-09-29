package com.diafarms.ml.DTO;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Part des salaires payés attribuée à un projet (GET /main-oeuvre/projets/{uid}/cout).
// Coût analytique : les salaires restent des sorties de la ferme en Comptabilité.
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoutMainOeuvreDTO {
    private double total;
    private List<Ligne> lignes;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Ligne {
        private String periode; // AAAA-MM
        private String employeNom;
        private double montantPaye; // salaire du mois
        private double partAffectation; // jours affectés à ce projet
        private int joursAffectes;
        private int joursDuMois;
        private double partProrata; // part du reste du mois, au prorata des sujets vivants
        private double pourcentageProrata; // 0..100
        private double total;
    }
}
