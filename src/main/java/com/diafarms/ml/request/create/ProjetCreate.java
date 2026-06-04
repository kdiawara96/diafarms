package com.diafarms.ml.request.create;


import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class ProjetCreate{
    // Étape 1 : Informations générales
    private String titre;
    private String nomResponsable; // Correspond à ton champ responsable String si conservé
    private LocalDate dateDebut;
    private LocalDate dateFinPrevue;
    private Integer nbSujets;
    private Double puSujet;         // Prix Unitaire du sujet
    private String objectif;        // "ponte", "chair", etc.
    private String race;            // Nom ou ID de la race sélectionnée
    private String responsableProduction; // Nom, Username ou ID du user Prod
    private String responsableFinance;    // Nom, Username ou ID du user Finance
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