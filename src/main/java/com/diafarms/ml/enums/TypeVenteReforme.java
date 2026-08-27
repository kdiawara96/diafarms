package com.diafarms.ml.enums;

// Mode de tarification d'une VenteReforme — voir VenteReforme.typeVente. Ne change
// jamais la répartition entre projets contributeurs (toujours par nombreSujets), ni
// le calcul de montant côté serveur (toujours transmis directement par le client) :
// seul le sens de VenteReforme.prixUnitaire et la présence de poidsTotalKg en
// dépendent.
public enum TypeVenteReforme {
    TETE, // prixUnitaire = prix par sujet
    KILO  // prixUnitaire = prix par kg, poidsTotalKg obligatoire
}
