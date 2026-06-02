package com.diafarms.ml.models;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Objectif;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "projets")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Projets {
    
  @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;   // ex: "P-001" → correspond à project.id du front

    @Column(name = "code", nullable = false, length = 12)
    private String code;

    @Column(name = "titre", nullable = false, length = 100)
    private String titre;

    @Column(name = "responsable", length = 100)
    private String responsable;

    @Column(name = "fournisseurs_poussins", length = 100)
    private String fournisseurs_poussins;

    @Column(name = "date_debut")
    private LocalDate debut;

    @Column(name = "date_fin_prevue")
    private LocalDate finPrevue;

    @Column(name = "nb_sujets")
    private Integer nbSujets;

    @Column(name = "pu_sujet")
    private Double puSujet;

    @Column(name = "autres_depense")
    private Double autresDepense; 

    @Column(name = "chiffre_affaire")
    private Double chiffreAffaire;                 // chiffre d'affaire ex: "1.56M"

    @Enumerated(EnumType.STRING)
    @Column(name = "objectif", nullable = false, length = 20)
    private Objectif objectif;

    @Embedded
    private Initialisation initialisation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "production_user_id")
    private Utilisateurs responsableProduction;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "finance_user_id")
    private Utilisateurs responsableFinance;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "race_id", nullable = false)
    private Race race;

    @OneToMany(mappedBy = "projet", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OccupationBatiment> occupations = new ArrayList<>();

    @OneToMany(mappedBy = "projet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Alimentation> alimentations = new ArrayList<>();

    @OneToMany(mappedBy = "projet", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<Vaccination> vaccinations = new ArrayList<>();

}
