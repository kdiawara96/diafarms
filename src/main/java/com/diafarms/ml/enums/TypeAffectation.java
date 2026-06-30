package com.diafarms.ml.enums;

public enum TypeAffectation {
    COMMUN,
    DEDIE;

    public static TypeAffectation fromString(String value) {
        return switch (value.toLowerCase()) {
            case "commun"      -> COMMUN;
            case "dedie"    -> DEDIE;
            default -> throw new IllegalArgumentException("Type d'affectation invalide : " + value);
        };
    }
}
