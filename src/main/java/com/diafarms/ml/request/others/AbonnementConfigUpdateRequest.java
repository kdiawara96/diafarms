package com.diafarms.ml.request.others;

import lombok.Data;

// Tous les champs optionnels — mise à jour partielle (seuls les champs non-null sont
// appliqués), même convention que MagasinCreate/Update dans ce projet.
@Data
public class AbonnementConfigUpdateRequest {
    private Double prixMensuel;
    private Double prixAnnuel;
    private Integer dureeEssaiJours;
    private Integer dureeGraceHeures;
}
