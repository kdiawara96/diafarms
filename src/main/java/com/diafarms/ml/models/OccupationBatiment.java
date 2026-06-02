package com.diafarms.ml.models;

import java.time.LocalDate;

import jakarta.persistence.Column;
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
@Table(name = "occupations_batiments")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class OccupationBatiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batiment_id", nullable = false)
    private Batiment batiment;

    @Column(name = "date_entree", nullable = false)
    private LocalDate dateEntree; // Quand les volailles entrent dans le bâtiment

    @Column(name = "date_sortie")
    private LocalDate dateSortie; // Quand le bâtiment est libéré (ex: après la réforme)

    @Column(name = "nb_sujets_dans_batiment")
    private Integer nbSujetsDansBatiment; // Optionnel : pour savoir combien de poules sont dans ce bâtiment précis
}