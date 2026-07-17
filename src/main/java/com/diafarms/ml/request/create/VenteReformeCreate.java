package com.diafarms.ml.request.create;

import lombok.Data;

// Pas de projetUniqueId : la répartition entre projets contributeurs est calculée
// automatiquement côté serveur — voir VenteReformeImpl.
@Data
public class VenteReformeCreate {
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer nombreSujets;
    private Double prixUnitaire; // optionnel, informatif
    private Double montant;
}
