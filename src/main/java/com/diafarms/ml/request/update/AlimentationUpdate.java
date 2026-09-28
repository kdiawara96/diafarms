package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class AlimentationUpdate {

    private String nomAliment;
    private Double sac;
    private Double quantiteKg;
    private Double coutTotal;
    private String dateDistribution;
    private String heure; // "HH:mm", optionnel
    private String observations;
    private String batimentUniqueId; // optionnel
    private String fournisseur; // optionnel
    private String projetUniqueId; // optionnel : changer de projet (refusé si l'ancien a déjà consommé cet aliment)
    private String typeAliment; // optionnel : DEMARRAGE | CROISSANCE | PONTE | AUTRE
}
