package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeSoin;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Entité unifiée pour toute saisie de santé sur un projet : vaccin, médicament ou
// autre soin — remplace les deux anciennes entités séparées Soins/Vaccination
// (fusionnées le 2026-09-16, voir migration SQL associée). Les deux existaient
// historiquement séparées uniquement pour ne pas risquer de casser Vaccination en
// développant Soins par-dessus ; ce risque n'existe plus, donc une seule table.
//
// Quand type = VACCINATION : prixUnitaire est renseigné et coutTotal est calculé
// automatiquement (quantite * prixUnitaire), comme le faisait l'ancienne entité
// Vaccination — voir calculerCoutTotal(). Pour MEDICAMENT/AUTRE, coutTotal reste une
// saisie manuelle (pas de prix unitaire connu en général).
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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TypeSoin type;

    // Nom du produit — nom du vaccin quand type = VACCINATION.
    @Column(nullable = false, length = 150)
    private String produit;

    private Double quantite;

    // Renseigné seulement pour un vaccin (nombre de doses/flacons) — voir
    // calculerCoutTotal() ci-dessous.
    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    @Column(name = "cout_total")
    private Double coutTotal;

    // Pipe-joined, ex: "Oral | Injection" — renseigné seulement pour un vaccin.
    @Column(name = "mode_administration", length = 255)
    private String modeAdministration;

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

    @PrePersist
    @PreUpdate
    public void calculerCoutTotal() {
        if (this.quantite != null && this.prixUnitaire != null) {
            this.coutTotal = this.quantite * this.prixUnitaire;
        }
    }
}
