package com.diafarms.ml.enums;

// Mode de tarification d'une VenteReforme — voir VenteReforme.typeVente. Ne change
// jamais la répartition entre projets contributeurs (toujours par nombreSujets) ni le
// stock (toujours en sujets). Seuls le sens de VenteReforme.prixUnitaire (prix par sujet
// ou par kg) et la présence de poidsTotalKg en dépendent. Montant : transmis par le
// client sur une vente directe ; calculé par le serveur (poids x prix/kg) à la livraison
// d'une commande au kilo (CommandeServiceImpl.livrer) et, sans montant explicite, quand
// le poids ou le prix d'une vente KILO est modifié (VenteReformeImpl.update). Sert aussi
// de tarification d'une Commande (Commande.tarification).
public enum TypeVenteReforme {
    TETE, // prixUnitaire = prix par sujet
    KILO  // prixUnitaire = prix par kg, poidsTotalKg obligatoire
}
