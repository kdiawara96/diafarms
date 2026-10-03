package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Page Reporting (GET /reporting, voir ReportingService) : tout est calculé côté serveur,
// pour la période choisie et pour la période précédente de même durée.
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReportingDTO {
    private LocalDate dateDebut;
    private LocalDate dateFin;
    private LocalDate precedentDebut;
    private LocalDate precedentFin;
    // Projet filtré (null = tous les projets visibles).
    private String projetUniqueId;
    // true : toute la ferme (pas de filtre Projet, utilisateur non restreint à ses projets) ;
    // la ligne « Commun » et les dépenses communes n'existent que dans cette vue.
    private boolean vueFerme;
    // false : l'utilisateur ne voit pas les salaires, la main-d'œuvre n'est pas répartie.
    private boolean mainOeuvreVisible;

    private Bloc periode;
    private Bloc precedent;

    private List<Jour> seriesJour;
    private List<Semaine> seriesSemaine;
    private List<LigneProjet> parProjet;
    // Ferme entière seulement : argent qui n'appartient à aucun Projet (dépenses de site /
    // de toute la ferme non réparties, avances et acomptes, autres entrées communes).
    private Argent commun;
    private List<Montant> depensesParCategorie;
    private List<Montant> depensesParRattachement;
    private List<Vendeur> parVendeur;

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Bloc {
        private Argent argent;
        private Elevage elevage;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Argent {
        // Ventes de la période (œufs, réforme, fientes et autres), payées ou non.
        private double vendu;
        // Argent reçu dans la période (paiements des clients, ventes au comptant, autres entrées).
        private double encaisse;
        // Ce qui reste dû aujourd'hui sur les ventes de la période.
        private double resteAEncaisser;
        // Entrées qui ne sont pas des ventes (subvention, apport...), comptées dans le résultat.
        private double autresEntrees;
        // Dépenses validées (hors remboursements aux clients), main-d'œuvre comprise pour un Projet.
        private double depenses;
        // Part des salaires répartie sur le(s) Projet(s) (déjà dans depenses). null : non visible.
        private Double mainOeuvre;
        // vendu + autresEntrees - depenses.
        private double resultat;
        // Dépenses de site / de toute la ferme non réparties sur un Projet (non comptées dans
        // depenses d'un Projet). null : pas visible pour cet utilisateur.
        private Double depensesCommunes;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Elevage {
        private int effectifDebut;
        private int effectifFin;
        private long oeufsCollectes;
        private long oeufsCasses;
        private double alveoles;
        // Projets de ponte seulement ; null sans ponte.
        private Double tauxPonteMoyen;
        private long mortes;
        private Double mortalitePct;
        private double alimentKg;
        private Double alimentParAlveoleKg;
        private Double coutAlveole;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Jour {
        private LocalDate date;
        private long oeufs;
        private long mortes;
        private double alimentKg;
        private Double tauxPonte;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Semaine {
        private LocalDate debut;
        private LocalDate fin;
        private double encaisse;
        private double depenses;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class LigneProjet {
        private String projetUniqueId;
        private String code;
        private String nom;
        private String type;
        private Elevage elevage;
        private Argent argent;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Montant {
        private String cle;
        private String libelle;
        private double montant;
    }

    @Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
    public static class Vendeur {
        private String vendeurUniqueId;
        private String nom;
        private long nbVentes;
        private double vendu;
    }
}
