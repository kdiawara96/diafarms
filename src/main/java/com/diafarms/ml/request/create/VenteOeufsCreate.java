package com.diafarms.ml.request.create;

import lombok.Data;

// Pas de projetUniqueId : la répartition entre projets contributeurs est calculée
// automatiquement côté serveur, au prorata du stock disponible de chacun DANS le
// magasin choisi — voir VenteOeufsImpl.
@Data
public class VenteOeufsCreate {
    private String date;
    private String heure; // "HH:mm", optionnel
    private String magasinUniqueId; // obligatoire, plafonne la quantité vendable
    private Integer quantiteOeufs;
    private Double prixUnitaire; // optionnel, informatif
    private Double montant; // théorique (quantité × prix, ou saisi librement)
    private Double montantRapporte; // optionnel : ce que le vendeur a réellement rapporté
}
