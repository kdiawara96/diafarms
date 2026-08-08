package com.diafarms.ml.request.create;

import java.util.List;

import lombok.Data;

@Data
public class MagasinVenteCreate {
    private String nom;
    private String description; // optionnel
    private List<String> vendeurUniqueIds; // optionnel, VENTE role attendu (non vérifié strictement)
}
