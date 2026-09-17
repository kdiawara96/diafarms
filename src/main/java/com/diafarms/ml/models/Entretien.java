package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.NiveauEntretien;
import com.diafarms.ml.enums.TypeEntretien;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Journal des actions d'entretien/maintenance — un pendant de Soins pour
// l'infrastructure plutôt que le cheptel. Volontairement PAS lié à Projets : nettoyer
// un poulailler ou débroussailler le site n'a rien à voir avec un lot en cours (un
// poulailler peut être vide/inoccupé au moment de son entretien). Volontairement
// aucun montant ici non plus : voir Soins pour la même règle — la Production suit le
// fait, le coût réel se saisit en Comptabilité (catégories "Entretien / Maintenance",
// "Copeau" déjà existantes, voir CreateTransactionDialog côté web).
@Entity
@Table(name = "entretiens")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Entretien {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    private LocalTime heure;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private NiveauEntretien niveau;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TypeEntretien type;

    // Ex: "Nettoyage complet poulailler A", "Débroussaillage clôture nord".
    @Column(nullable = false, length = 255)
    private String description;

    @Column(length = 500)
    private String observations;

    // Obligatoire si niveau = BATIMENT, toujours null si niveau = SITE (voir
    // EntretienImpl.create/update).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batiment_id")
    private Batiment batiment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
