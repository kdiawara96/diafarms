package com.diafarms.ml.request.update;

import java.util.List;

import lombok.Data;

@Data
public class SoinsUpdate {
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure;
    private String type; // "VACCINATION" | "MEDICAMENT" | "AUTRE"
    private String produit;
    private Double quantite;
    private Double prixUnitaire;
    private Double coutTotal;
    private List<String> modeAdministration;
    private String observations;
    private Boolean depuisStock; // true = pris dans le stock de médicaments du projet
    private String unite; // unité du médicament en stock (flacon, ml, sachet...)
}
