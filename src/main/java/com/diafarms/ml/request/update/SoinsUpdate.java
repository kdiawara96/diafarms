package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class SoinsUpdate {
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure;
    private String type;
    private String produit;
    private Double quantite;
    private Double coutTotal;
    private String observations;
}
