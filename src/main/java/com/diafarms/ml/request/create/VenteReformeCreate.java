package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class VenteReformeCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer nombreSujets;
    private Double prixUnitaire; // optionnel, informatif
    private Double montant;
}
