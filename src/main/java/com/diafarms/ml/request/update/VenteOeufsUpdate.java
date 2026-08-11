package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class VenteOeufsUpdate {
    private String date;
    private String heure;
    private Integer quantiteOeufs;
    private Double prixUnitaire;
    private Double montant;
    private Double montantRapporte;
    // null = non renseigné, pas touché ; "" (chaîne vide) = détache explicitement le
    // client de la vente ; sinon = nouveau client — même convention que
    // CollecteOeufsUpdate.batimentUniqueId.
    private String clientUniqueId;
}
