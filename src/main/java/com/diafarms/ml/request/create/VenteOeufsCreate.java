package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class VenteOeufsCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer quantiteOeufs;
    private Double prixUnitaire; // optionnel, informatif
    private Double montant;
}
