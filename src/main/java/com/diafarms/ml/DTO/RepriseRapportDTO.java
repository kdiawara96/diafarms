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
    // Lignes créées sur les factures d'avant la refonte (une par facture) : bloquent la
    // refacturation de leur vente et donnent une cible au paiement de la facture.
    private int lignesFacturesCreees;
    // Ventes à un client d'avant la refonte SANS montant rapporté (août 2026) : l'ancien
    // modèle les considérait payées ; la reprise crée pour chacune un paiement client du
    // montant de la vente. Listées une par une pour relecture pendant la simulation.
    private List<VenteSansMontantRapporte> ventesSansMontantRapporte = new ArrayList<>();
    private List<String> avertissements = new ArrayList<>();

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class VenteSansMontantRapporte {
        private String type; // VENTE_OEUFS / VENTE_REFORME
        private String venteUniqueId;
        private String clientUniqueId;
        private String clientNom;
        private java.time.LocalDate date;
        private double montant;
    }

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
