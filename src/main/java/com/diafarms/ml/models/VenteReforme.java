package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalTime;

import com.diafarms.ml.commons.Initialisation;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Vente réforme (sujets vivants vendus), plafonnée par l'effectif vivant =
// nbSujets initial du projet moins la mortalité cumulée moins les ventes déjà
// enregistrées. Chaque création génère automatiquement une Transaction "entrée"
// liée (voir TransactionService.createFromSource).
@Entity
@Table(name = "ventes_reforme")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VenteReforme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    private LocalTime heure;

    @Column(name = "nombre_sujets", nullable = false)
    private Integer nombreSujets;

    // Informatif seulement — voir VenteOeufs.prixUnitaire.
    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    @Column(nullable = false)
    private Double montant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batiment_id")
    private Batiment batiment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
