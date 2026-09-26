package com.diafarms.ml.DTO;

import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Rapport de la reprise « acompte réservé » (voir RepriseAcompteReserveService). Même
// forme en simulation (rien n'est écrit) et en exécution réelle.
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RepriseAcompteReserveRapportDTO {
    private boolean execute;
    private int clientsConcernes;
    private int imputationsRetirees;
    private double montantRetire;
    private List<Ligne> lignes = new ArrayList<>();
    private List<String> avertissements = new ArrayList<>();

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class Ligne {
        private String ferme;
        private String clientUniqueId;
        private String clientNom;
        private CompteClientDTO avant;
        private CompteClientDTO apres;
        private List<String> mouvements = new ArrayList<>();
    }
}
