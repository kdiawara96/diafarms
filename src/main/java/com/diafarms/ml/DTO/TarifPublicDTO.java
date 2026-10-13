package com.diafarms.ml.DTO;

// Prix et règles du crédit affichés sur le site cocoricoferme.com (public, sans connexion) :
// le site suit ainsi les réglages de la console au lieu de les écrire en dur.
public record TarifPublicDTO(
        double prixParPoule,
        double prixMinimumMensuel,
        int arrondi,
        int seuilSurDevis,
        double bonusSeuil,
        double bonusPourcent,
        double creditParrainage,
        int dureeEssaiJours,
        int delaiGraceJours) {
}
