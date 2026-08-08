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

@Entity
@Table(name = "collectes_oeufs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CollecteOeufs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    private LocalTime heure;

    @Column(name = "oeufs_collectes", nullable = false)
    private Integer oeufsCollectes;

    @Column(name = "oeufs_casses", nullable = false)
    private Integer oeufsCasses = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    // Bâtiment d'élevage où la collecte a eu lieu (via OccupationBatiment du projet) —
    // distinct de batimentStockage ci-dessous : ici on répond "où sont les poules",
    // pas "où sont physiquement les œufs une fois ramassés".
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batiment_id")
    private Batiment batiment;

    // Bâtiment de STOCKAGE (Batiment.TypeBatiment.STOCKAGE) où les œufs sont
    // physiquement déposés après collecte — c'est CE stock, par bâtiment, qui
    // plafonne les transferts vers un magasin de vente (MagasinTransfert), pas le
    // stock théorique du projet. Nullable pour compat des collectes antérieures à ce
    // champ (leur contribution reste alors invisible aux transferts, voir
    // MagasinTransfertServiceImpl.disponibleParProjetDansBatimentStockage).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batiment_stockage_id")
    private Batiment batimentStockage;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
