package com.diafarms.ml.models;

import java.time.LocalDate;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Salaire.ModePaiement;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne = un taux qui a été en vigueur pour ce Salaire, sur une période donnée
// (dateEffective -> dateFin, dateFin null = toujours en vigueur). Créée à chaque fois
// que SalaireServiceImpl.definir() change réellement le mode/taux d'un Salaire — sans
// ça, payer une période en retard après une augmentation de salaire proposerait à tort
// le NOUVEAU taux pour un mois où l'ANCIEN était encore en vigueur (voir
// SalaireServiceImpl.resolveTauxPourPeriode).
@Entity
@Table(name = "salaire_historiques")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class SalaireHistorique {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salaire_id", nullable = false)
    private Salaire salaire;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_paiement", nullable = false, length = 20)
    private ModePaiement modePaiement;

    @Column(name = "taux_base", nullable = false)
    private Double tauxBase;

    @Column(name = "date_effective", nullable = false)
    private LocalDate dateEffective;

    // null = toujours en vigueur (le taux "courant") — voir
    // SalaireServiceImpl.definir, qui ferme l'ancien enregistrement actif avant d'en
    // ouvrir un nouveau.
    @Column(name = "date_fin")
    private LocalDate dateFin;

    @Embedded
    private Initialisation initialisation;
}
