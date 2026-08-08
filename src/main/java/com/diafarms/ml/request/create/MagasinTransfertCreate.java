package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class MagasinTransfertCreate {
    private String magasinUniqueId;
    private String projetUniqueId;
    private String type; // "OEUFS" ou "REFORME"
    private Integer quantite;
    private String date; // optionnel, défaut = aujourd'hui
}
