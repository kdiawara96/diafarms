package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne = une vente (VenteOeufs ou VenteReforme) facturée, snapshot figé au moment
// de la génération — description/quantité/prix/montant ne bougent plus après, même si
// la vente d'origine est modifiée plus tard (même principe que Facture, voir Facture.java).
// Une facture "legacy" (Facture.legacy = true, générée avant cette refonte) n'a AUCUNE
// ligne : FactureServiceImpl reconstruit alors une ligne unique pour l'affichage/le PDF
// à partir des champs historiques de Facture (description/quantite/prixUnitaire/montantTotal).
@Entity
@Table(name = "factures_lignes")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class FactureLigne {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facture_id", nullable = false)
    private Facture facture;

    // Toujours VENTE_OEUFS ou VENTE_REFORME ici (jamais REMBOURSEMENT — voir
    // CibleImputation) : c'est aussi le type utilisé pour interroger
    // CompteClientService.payeVente et calculer le montant payé de cette ligne.
    @Enumerated(EnumType.STRING)
    @Column(name = "vente_type", nullable = false, length = 20)
    private CibleImputation venteType;

    @Column(name = "vente_unique_id", nullable = false, length = 50)
    private String venteUniqueId;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "quantite")
    private Integer quantite;

    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    @Column(name = "montant", nullable = false)
    private Double montant;

    @Embedded
    private Initialisation initialisation;
}
