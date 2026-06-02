package com.diafarms.ml.enums;

public enum AlertType {
    MORTALITE("Mortalité"),
    ALIMENTATION("Alimentation"),
    VACCINATION("Vaccination"),
    METEO("Météo");

    private final String label;

    AlertType(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}