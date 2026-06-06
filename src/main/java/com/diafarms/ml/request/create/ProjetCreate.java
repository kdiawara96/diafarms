package com.diafarms.ml.request.create;


import lombok.Data;
import java.util.List;

@Data
public class ProjetCreate {
    
    // Étape 1 : Informations générales
    private String titre;
    private String nomResponsable; 
    private String dateDebut;        // Changé en String pour le Front
    private String dateFinPrevue;    // Changé en String pour le Front
    private Integer nbSujets;
    private Double puSujet;         
    private String objectif;        
    private Long raceId;             // Changé en Long pour correspondre à form.raceId
    private Long responsableProductionId; // Changé en Long pour form.responsableProductionId
    private Long responsableFinanceId;    // Changé en Long pour form.responsableFinanceId
    private String fournisseursPoussins;
    private Double autresDepense;

    // Étape 2 : Alimentation (Initiale)
    private String alimentNom;
    private Integer sac;
    private Double quantiteKg;
    private Double coutTotalAliment;
    private String observations;

    // Étape 3 : Liste des Vaccinations
    private List<VaccinCreate> vaccins;

    // Étape 4 : Liste des Occupations de Bâtiments
    private List<OccupationCreate> occupations;
}