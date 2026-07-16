package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class VenteOeufsUpdate {
    private String date;
    private String heure;
    private Integer quantiteOeufs;
    private Double prixUnitaire;
    private Double montant;
}
