package com.diafarms.ml.enums;

public enum Objectif {
    PONTE,
    REFORME,
    MIXTE;

        public static Objectif fromString(String value) {
        return switch (value.toLowerCase()) {
            case "ponte"      -> PONTE;
            case "reforme"    -> REFORME;
            case "mixte"      -> MIXTE;
            default -> throw new IllegalArgumentException("Objectif invalide : " + value);
        };
    }
}



