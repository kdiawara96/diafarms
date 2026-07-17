package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.diafarms.ml.commons.Initialisation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Vente réforme — acte Finance, plafonnée par le total réformé (Reforme, Production)
// de TOUTE LA FERME moins déjà vendu (voir VenteReformeImpl.getStock). Répartie au
// prorata de l'effectif réformé disponible de chaque projet contributeur — voir
// VenteOeufs pour le détail du mécanisme.
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
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @OneToMany(mappedBy = "venteReforme", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VenteReformeRepartition> repartitions;

    @Embedded
    private Initialisation initialisation;
}
