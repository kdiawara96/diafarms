package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class ReformeUpdate {
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure;
    private Integer nombreSujets;
    private String cause;
    private String magasinStockageUniqueId; // optionnel : déplace les réformés vers ce magasin de stockage
}
