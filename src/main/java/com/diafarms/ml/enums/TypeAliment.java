package com.diafarms.ml.enums;

// Type d'aliment acheté (Alimentation.typeAliment, facultatif). Choisi dans la sortie
// d'argent catégorie "Achat d'aliment" : sert aussi à pré-remplir nomAliment quand il
// n'est pas saisi (voir AlimentationImpl.save).
public enum TypeAliment {
    DEMARRAGE("Démarrage"),
    CROISSANCE("Croissance"),
    PONTE("Ponte"),
    AUTRE("Autre");

    private final String label;

    TypeAliment(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }

    // null / vide = pas de type ; valeur inconnue = 400 (message clair plutôt qu'une 500).
    public static TypeAliment parse(String valeur) {
        if (valeur == null || valeur.isBlank()) return null;
        try {
            return TypeAliment.valueOf(valeur.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Type d'aliment inconnu : " + valeur
                    + " (valeurs possibles : DEMARRAGE, CROISSANCE, PONTE, AUTRE).");
        }
    }
}
