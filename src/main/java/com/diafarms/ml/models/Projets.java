package com.diafarms.ml.models;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.StatutProjets;
import com.fasterxml.jackson.annotation.JsonProperty;

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
@Inheritance(strategy = InheritanceType.JOINED)
public class Projets {
    
  @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;   // ex: "P-001" → correspond à project.id du front

    @Column(name = "titre", nullable = false, length = 100)
    private String titre;

    @Column(name = "responsable", length = 100)
    private String responsable;

    @Column(name = "date_debut")
    private LocalDate debut;

    @Column(name = "date_fin_prevue")
    private LocalDate finPrevue;

    @Column(name = "nb_poules")
    private Integer poules;

    @Column(name = "oeufs_par_jour")
    private Integer oeufsJour;

    @Column(name = "mortalite_7j")
    private Double mortalite7j;        // ex: "0.2%"

    @Column(name = "stock_aliment")
    private String stockAliment;

    @Column(name = "stock_alerte")
    private Boolean stockAlerte;

    @Column(name = "ca")
    private Double ca;                 // ex: "1.56M"

    @Column(name = "marge")
    private Double marge;

    @Column(name = "statut")
    @Enumerated(EnumType.STRING)
    private StatutProjets statut;

    
    @Embedded
    private Initialisation initialisation;
}
