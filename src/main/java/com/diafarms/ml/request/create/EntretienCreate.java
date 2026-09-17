package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class EntretienCreate {
    private String batimentUniqueId; // obligatoire si niveau = BATIMENT, ignoré sinon
    private String date;
    private String heure; // "HH:mm", optionnel
    private String niveau; // "BATIMENT" | "SITE"
    private String type; // "NETTOYAGE" | "COPEAU" | "AUTRE" — ignoré et forcé à AUTRE si niveau = SITE
    private String description;
    private String observations;
}
