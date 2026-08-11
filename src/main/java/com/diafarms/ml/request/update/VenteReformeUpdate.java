package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class VenteReformeUpdate {
    private String date;
    private String heure;
    private Integer nombreSujets;
    private Double prixUnitaire;
    private Double montant;
    private Double montantRapporte;
    // null = non renseigné, pas touché ; "" (chaîne vide) = détache explicitement le
    // client de la vente ; sinon = nouveau client — voir VenteOeufsUpdate.clientUniqueId.
    private String clientUniqueId;
}
