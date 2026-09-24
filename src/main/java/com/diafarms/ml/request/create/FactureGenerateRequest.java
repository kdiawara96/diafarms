package com.diafarms.ml.request.create;

import java.util.List;

import lombok.Data;

@Data
public class FactureGenerateRequest {
    // Deux formes acceptées (voir FactureServiceImpl.genererDepuis) :
    // - `ventes` + `clientUniqueId` : liste explicite de ventes du même client
    //   (sourceType résultant = VENTES) ;
    // - `sourceType` = "COMMANDE" + `sourceUniqueId` : facture les livraisons actives
    //   non encore facturées de cette commande ;
    // - `sourceType` = "VENTE_OEUFS"/"VENTE_REFORME" + `sourceUniqueId` : compatibilité
    //   avec l'ancien web, traité comme une liste `ventes` à un seul élément.
    private String sourceType;
    private String sourceUniqueId;

    private String clientUniqueId;
    private List<VenteRef> ventes;

    @Data
    public static class VenteRef {
        private String type; // "VENTE_OEUFS" | "VENTE_REFORME"
        private String uniqueId;
    }
}
