package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class CollecteOeufsCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel — bâtiment d'élevage (où sont les poules)
    private String batimentStockageUniqueId; // obligatoire — bâtiment de stockage (où vont les œufs)
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer oeufsCollectes;
    private Integer oeufsCasses;
}
