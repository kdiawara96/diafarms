package com.diafarms.ml.enums;

// Remplace les anciennes valeurs libres "Vaccin"/"Médicament"/"Autre" de Soins.type,
// et sert aussi à distinguer une entrée "Vaccination" (protocole chiffré : doses +
// prix unitaire, coût calculé automatiquement) au sein de la table unifiée soins —
// voir Soins.java pour l'historique de la fusion avec l'ancienne entité Vaccination.
public enum TypeSoin {
    VACCINATION("Vaccination"),
    MEDICAMENT("Médicament"),
    AUTRE("Autre");

    private final String label;

    TypeSoin(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
