package com.diafarms.ml.request.create;

import lombok.Data;

// Poulailler à créer en même temps qu'un investissement (voir
// InvestissementServiceImpl.appliquerPoulaillers) : mêmes champs que la création
// depuis la page Poulaillers.
@Data
public class NouveauPoulaillerRequest {
    private String nom;
    private Integer capacite;
    private Double superficieM2;
    private String description;
}
