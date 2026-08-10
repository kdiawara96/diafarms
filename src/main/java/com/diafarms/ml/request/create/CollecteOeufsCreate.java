package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class CollecteOeufsCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel — bâtiment d'élevage/poulailler (où sont les poules)
    private String magasinStockageUniqueId; // obligatoire — magasin de stockage (où vont les œufs)
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer oeufsCollectes;
    private Integer oeufsCasses;
}
