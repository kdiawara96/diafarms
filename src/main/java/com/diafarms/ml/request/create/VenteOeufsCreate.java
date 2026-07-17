package com.diafarms.ml.request.create;

import lombok.Data;

// Pas de projetUniqueId : la répartition entre projets contributeurs est calculée
// automatiquement côté serveur, au prorata du stock disponible de chacun — voir
// VenteOeufsImpl.
@Data
public class VenteOeufsCreate {
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer quantiteOeufs;
    private Double prixUnitaire; // optionnel, informatif
    private Double montant;
}
