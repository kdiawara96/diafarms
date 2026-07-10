package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class ConsommationAlimentUpdate {
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure;
    private Double quantiteKg;
}
