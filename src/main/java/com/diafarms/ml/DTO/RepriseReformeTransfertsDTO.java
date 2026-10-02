package com.diafarms.ml.DTO;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Rapport de la reprise des transferts de réformés vers un point de vente (voir
// ReformeTransfertsManquantsService). Même forme en simulation et en exécution.
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RepriseReformeTransfertsDTO {
    private boolean execute;
    private String farmUniqueId;
    private String farmNom;
    private String pointDeVenteUniqueId;
    private String pointDeVenteNom;
    // Renseigné si rien n'a pu (ou ne pourrait) être transféré : point de vente indéterminé.
    private String erreur;
    private int totalATransferer;
    private int totalTransfere;
    private int transfertsCrees;
    private List<Projet> projets = new ArrayList<>();

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Projet {
        private String projetUniqueId;
        private String projetCode;
        private String projetTitre;
        private int sujetsReformes;     // réformes actives du projet
        private int dejaTransferes;     // transferts REFORME actifs du projet (manuels + automatiques)
        private int manquant;           // sujetsReformes - dejaTransferes (si positif)
        private int transferes;         // effectivement transférés par ce passage
        private int transfertsCrees;
    }
}
