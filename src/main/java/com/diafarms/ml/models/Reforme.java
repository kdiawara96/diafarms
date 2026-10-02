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

    // Magasin de STOCKAGE où vont les sujets réformés, comme les œufs d'une collecte
    // (voir CollecteOeufs.magasinStockage et ReformeStockage) : ils passent ensuite au
    // point de vente par défaut de ce magasin (transfert REFORME lié, automatique) ou par
    // un transfert manuel. Null = réforme ancienne (avant cette règle) ou sans magasin
    // déterminable (ancien téléphone, ferme sans magasin de stockage).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_stockage_id")
    private Magasin magasinStockage;

    // Point de vente où se trouvent maintenant les sujets de cette réforme (celui de son
    // transfert lié actif, tenu à jour par ReformeStockage) ; null s'ils sont encore au
    // magasin de stockage. Pour les réformes du 2 octobre 2026 (envoi direct), c'est le
    // point de vente choisi alors.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_vente_id")
    private Magasin magasinVente;

    @Embedded
    private Initialisation initialisation;
}
