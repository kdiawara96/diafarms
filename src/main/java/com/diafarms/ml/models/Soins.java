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

// Saisie rapide de soins (vaccins/médicaments/autre), indépendante de l'historique
// détaillé Vaccination (déjà utilisé ailleurs, avec son propre coût et son propre
// suivi) — volontairement une entité séparée pour ne pas risquer de casser ce qui
// fonctionne déjà.
@Entity
@Table(name = "soins")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Soins {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    private LocalTime heure;

    @Column(nullable = false, length = 30)
    private String type; // "Vaccin" | "Médicament" | "Autre"

    @Column(nullable = false, length = 150)
    private String produit;

    private Double quantite;

    @Column(name = "cout_total")
    private Double coutTotal;

    @Column(length = 500)
    private String observations;

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
