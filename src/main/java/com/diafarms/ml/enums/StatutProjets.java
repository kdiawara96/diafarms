package com.diafarms.ml.enums;

public enum StatutProjets {
    ACTIF,
    ARCHIVE,
    REFORME;

        public static StatutProjets fromString(String value) {
        return switch (value.toLowerCase()) {
            case "actif"      -> ACTIF;
            case "archive"    -> ARCHIVE;
            case "reforme"    -> REFORME;
            default -> throw new IllegalArgumentException("Statut invalide : " + value);
        };
    }
}



