package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class MagasinTransfertCreate {
    private String magasinUniqueId;
    // REFORME : requis, l'utilisateur choisit directement le projet source (pas de
    // notion de bâtiment de stockage pour les sujets réformés).
    private String projetUniqueId;
    // OEUFS : requis, l'utilisateur choisit le bâtiment de stockage source — la
    // répartition entre les projets qui y ont contribué est automatique côté serveur
    // (voir MagasinTransfertServiceImpl.create).
    private String batimentStockageUniqueId;
    private String type; // "OEUFS" ou "REFORME"
    private Integer quantite;
    private String date; // optionnel, défaut = aujourd'hui
}
