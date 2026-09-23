package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class VenteDiverseCreate {
    private String produit; // "FIENTES" ou "AUTRE"
    private String date; // "yyyy-MM-dd", aujourd'hui par défaut
    private Double quantite; // facultatif (sacs de fientes)
    private Double prixUnitaire; // facultatif, informatif
    private Double montant; // obligatoire, > 0
    private String description; // obligatoire pour AUTRE
}
