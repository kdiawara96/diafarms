package com.diafarms.ml.request.update;

import lombok.Data;

// null = champ inchangé. Le produit (fientes/autre) ne change jamais après création.
@Data
public class VenteDiverseUpdate {
    private String date;
    private Double quantite;
    private Double prixUnitaire;
    private Double montant;
    private String description;
}
