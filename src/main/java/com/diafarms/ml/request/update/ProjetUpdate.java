package com.diafarms.ml.request.update;


import lombok.Data;

@Data
public class ProjetUpdate {
    private String titre;
    private Long responsableId;      // Utilisateur RESPONSABLE — remplace l'ancien nomResponsable en texte libre
    private String dateDebut;        // Changé en String pour le Front
    private String dateFinPrevue;    // Changé en String pour le Front
    private Integer nbSujets;
    private Double puSujet;
    private String objectif;
    private Long raceId;             // Changé en Long pour correspondre à form.raceId
    private Long responsableProductionId;
    private Long responsableFinanceId;
    private String fournisseursPoussins;
    private Double autresDepense;
}
