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

// Vente d'œufs — acte Finance, plafonnée par le total collecté (CollecteOeufs,
// Production) de TOUTE LA FERME moins ce qui a déjà été vendu (voir
// VenteOeufsImpl.getStock). N'est pas rattachée à UN projet : elle est répartie au
// prorata du stock disponible de chaque projet contributeur (voir repartitions,
// VenteOeufsRepartition) — chaque part génère sa propre Transaction "entrée"
// directement liée à son projet, pour que le chiffre d'affaires par projet reste exact.
@Entity
@Table(name = "ventes_oeufs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VenteOeufs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    private LocalTime heure;

    @Column(name = "quantite_oeufs", nullable = false)
    private Integer quantiteOeufs;

    // Informatif seulement (moyenne/négociation) : le montant réellement encaissé
    // est celui de la Transaction générée, jamais recalculé depuis prixUnitaire.
    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    @Column(nullable = false)
    private Double montant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @OneToMany(mappedBy = "venteOeufs", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VenteOeufsRepartition> repartitions;

    @Embedded
    private Initialisation initialisation;
}
