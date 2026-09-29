package com.diafarms.ml.models;

import java.time.LocalDate;

import com.diafarms.ml.commons.Initialisation;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Affectation d'un employé (Personnel) à un projet sur une période : sert à répartir
// son salaire sur les projets (voir MainOeuvreService). Les jours du mois où il est
// affecté vont à ce projet ; le reste du mois est partagé entre les projets en cours
// au prorata de leurs sujets vivants. Une seule affectation à la fois par employé.
@Entity
@Table(name = "affectations_personnel")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class AffectationPersonnel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "personnel_id", nullable = false)
    private Personnel personnel;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    // null = toujours en cours.
    @Column(name = "date_fin")
    private LocalDate dateFin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
