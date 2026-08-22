package com.diafarms.ml.DTO;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne = un jour calendaire du cycle d'un projet — voir
// RapportJournalierServiceImpl. Reconstitue en un seul tableau ce qui est éclaté dans
// CollecteOeufs/Mortalite/Reforme/Transaction, à la manière du suivi manuel Excel qui a
// inspiré ce rapport (colonnes abrégées conservées en commentaire pour s'y retrouver).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RapportJournalierLigneDTO {
    private LocalDate date;
    private Integer nombrePoulesRestantes;      // NPR
    private Integer nombrePoulesMortes;         // NPM (du jour)
    private Integer nombreTotalOeufs;           // NTO (du jour)
    private Double tauxPonte;                   // TP = NTO / NPR
    private Integer nombreOeufsCasses;          // NEC (du jour)
    private Integer nombrePoulesMortesCumule;   // NPMC
    private Integer oeufsBonsCumule;            // (NTO - NEC - non utilisables) cumulé
    private Integer oeufsCassesCumule;          // NECC
    private Double nombreAlveolesJournalier;    // NJA = oeufs bons du jour / 30
    private Double prixUnitaireAlveole;         // PUA, dernier prix de vente connu
    private Double montantPotentielJournalier;  // MPJ = NJA * PUA
    private Double recetteJournaliere;          // RJ, ventes réelles attribuées ce jour
    private Double entreeJournaliereArgent;     // EJA, toute entrée validée ce jour
    private Double depenseJournaliereAliment;   // DJA
    private Double autresDepensesJournalieres;  // ADJ
    private Double differenceJournaliere;       // DJ = EJA - (DJA + ADJ)
    private Double cumulDifferenceJournaliere;  // CDJ
    private Double resteAAmortir;               // RM
    private Double tauxVariation;               // TV, variation % du reste à amortir
    private String commentaires;                // COM, descriptions ADJ/DJA du jour
}
