package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Emplacement physique de la ferme (ex: "Site de Bambey", "Site de Thiès") — regroupe
// poulaillers et magasins qui s'y trouvent (voir Batiment.site / Magasin.site). Une
// ferme avec un seul emplacement n'a jamais besoin d'en créer un : le champ est
// optionnel partout où il est utilisé. Latitude/longitude optionnelles, pensées pour
// une cartographie future des emplacements de la ferme — aucun usage aujourd'hui côté
// calculs, purement informatif.
@Entity
@Table(name = "sites")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Site {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "nom", nullable = false, length = 150)
    private String nom;

    @Column(name = "localisation", length = 255)
    private String localisation;

    @Column(name = "latitude")
    private Double latitude;

    @Column(name = "longitude")
    private Double longitude;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
