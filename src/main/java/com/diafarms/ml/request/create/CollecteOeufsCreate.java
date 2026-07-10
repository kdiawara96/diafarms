package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class CollecteOeufsCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer oeufsCollectes;
    private Integer oeufsCasses;
}
