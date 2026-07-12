package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class SoinsCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure; // "HH:mm", optionnel
    private String type; // "Vaccin" | "Médicament" | "Autre"
    private String produit;
    private Double quantite;
    private Double coutTotal;
    private String observations;
}
