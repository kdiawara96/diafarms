package com.diafarms.ml.enums;

// NETTOYAGE/COPEAU : uniquement pour NiveauEntretien.BATIMENT. AUTRE couvre à la fois
// "Autre à préciser" (bâtiment) et "Autres travaux" (site) — le champ description
// distingue le détail (débroussaillage, toilette, magasins, clôture, portes...).
public enum TypeEntretien {
    NETTOYAGE("Entretien / Nettoyage"),
    COPEAU("Remplacement de copeau"),
    AUTRE("Autre");

    private final String label;

    TypeEntretien(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
