package com.diafarms.ml.models;

import java.time.LocalDateTime;

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

// Une pesée d'une session : nombreSujets pesés ensemble pour poidsKg. Jamais modifiée
// une fois enregistrée ; seule l'annulation (false → true, définitive) est possible.
@Entity
@Table(name = "pesees")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Pesee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // UUID généré par le téléphone : l'unicité empêche les doublons en cas de renvoi.
    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "session_id", nullable = false)
    private SessionPesee session;

    @Column(name = "nombre_sujets", nullable = false)
    private Integer nombreSujets;

    @Column(name = "poids_kg", nullable = false)
    private Double poidsKg;

    @Column(name = "date_heure", nullable = false)
    private LocalDateTime dateHeure;

    @Column(name = "annulee", columnDefinition = "boolean not null default false")
    private Boolean annulee = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    @Embedded
    private Initialisation initialisation;
}
