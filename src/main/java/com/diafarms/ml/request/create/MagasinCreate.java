package com.diafarms.ml.request.create;

import java.util.List;

import lombok.Data;

@Data
public class MagasinCreate {
    private String nom;
    private String type; // "VENTE" ou "STOCKAGE", défaut VENTE si absent
    private String description; // optionnel
    private List<String> vendeurUniqueIds; // optionnel, VENTE role attendu (non vérifié strictement) — pertinent seulement pour type=VENTE
    // Seuils d'alerte stock bas (optionnels, null = pas d'alerte pour ce type dans ce magasin).
    private Integer seuilAlerteOeufs; // en ALVÉOLES, pas en œufs — voir Magasin.seuilAlerteOeufs
    private Integer seuilAlerteReforme;
    private Integer seuilAlerteAlveoles; // pertinent seulement pour type=STOCKAGE
}
