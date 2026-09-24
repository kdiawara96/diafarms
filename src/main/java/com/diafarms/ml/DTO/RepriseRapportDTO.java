package com.diafarms.ml.DTO;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Rapport de la reprise des données clients vers le circuit paiement/imputation (voir
// RepriseCircuitClientService). Même forme en simulation (rien n'est écrit) et en
// exécution réelle.
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RepriseRapportDTO {
    private boolean execute;
    private List<Ligne> lignes = new ArrayList<>();
    private int paiementsCrees;
    private int remboursementsCrees;
    private int recopiesFacturesRetirees;
    private int ventesConverties;
    private List<String> avertissements = new ArrayList<>();

    // Un client touché par la reprise, ou dont le solde recalculé diffère de l'ancien
    // solde stocké (soldes_client) d'au moins 1 FCFA. ecart = soldeApres − soldeAvant.
    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Ligne {
        private String clientUniqueId;
        private String clientNom;
        private double soldeAvant;
        private double soldeApres;
        private double ecart;
        private List<String> notes = new ArrayList<>();
    }
}
