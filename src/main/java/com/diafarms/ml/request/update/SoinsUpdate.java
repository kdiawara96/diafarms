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
}
