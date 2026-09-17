package com.diafarms.ml.enums;

// BATIMENT : lié à un poulailler précis (Entretien.batiment obligatoire).
// SITE : travaux sur la ferme dans son ensemble (débroussaillage, clôture, toilettes,
// magasins...) — jamais lié à un poulailler précis, voir EntretienImpl.
public enum NiveauEntretien {
    BATIMENT("Poulailler"),
    SITE("Site");

    private final String label;

    NiveauEntretien(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
