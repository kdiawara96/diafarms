package com.diafarms.ml.request.others;

import lombok.Data;

// Tous les champs optionnels — mise à jour partielle (seuls les champs non-null sont
// appliqués), même convention que MagasinCreate/Update dans ce projet.
@Data
public class AbonnementConfigUpdateRequest {
    private Double prixMensuel;
    private Double prixAnnuel;
    private Integer dureeEssaiJours;
    private Integer dureeGraceHeures; // historique, plus utilisé pour le calcul
    private Integer delaiGraceJours;
    // Prix par poule (voir commons/AbonnementTarif).
    private Double prixParPoule;
    private Double prixMinimumMensuel;
    private Integer moisOffertsAnnuel;
    private Integer arrondi;
    // Crédit prépayé.
    private Double bonusSeuil;
    private Double bonusPourcent;
    private Integer seuilSurDevis;
    private Double creditParrainage;
}
