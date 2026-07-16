package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class VenteReformeUpdate {
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure;
    private Integer nombreSujets;
    private Double prixUnitaire;
    private Double montant;
}
