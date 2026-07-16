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

// Réforme (sujets retirés du cheptel vivant pour abattage/vente), saisie Production
// au même titre que Mortalité — un pur comptage, jamais de prix. La vente réelle
// (avec montant) est une opération Finance distincte (VenteReforme), qui puise dans
// le total réformé de toute la ferme, pas projet par projet.
@Entity
@Table(name = "reformes")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Reforme {

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

    @Column(length = 500)
    private String cause;

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
