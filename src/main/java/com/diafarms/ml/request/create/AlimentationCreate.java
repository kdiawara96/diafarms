package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class AlimentationCreate {
    private String nomAliment;
    private Double sac;
    private Double quantiteKg;
    private Double coutTotal;
    private String dateDistribution;
    private String heure; // "HH:mm", optionnel
    private String observations;
    private String batimentUniqueId; // optionnel
    private String fournisseur; // optionnel
    private String typeAliment; // optionnel : DEMARRAGE | CROISSANCE | PONTE | AUTRE
    // Optionnel, non stocké : si quantiteKg est absent, quantiteKg = sac x poidsSacKg (50 par défaut).
    private Double poidsSacKg;
}