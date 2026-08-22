package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class CollecteOeufsUpdate {
    private String batimentUniqueId; // optionnel
    private String magasinStockageUniqueId; // optionnel (null = pas modifié)
    private String date;
    private String heure;
    private Integer oeufsCollectes;
    private Integer oeufsCasses;
    private Integer oeufsNonUtilisables;
}
