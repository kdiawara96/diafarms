package com.diafarms.ml.models;

import java.time.LocalDate;

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
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "investissements")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Investissement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long idInvestissement;

    @Column(name = "unique_id", nullable = false, unique = true)
    private String uniqueId;

    @Column(nullable = false)
    private String type; // Construction, Eau, Matériel, etc.

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Double montant;

    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(nullable = false)
    private String affectation; // Commun ou Projet spécifique

    /* --- ARCHITECTURE STANDARD DE TON PROJET --- */
    @Embedded
    private Initialisation initialisation ;
    

     // ============================================
    // RELATION AVEC UTILISATEUR (ManyToOne)
    // ============================================
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_utilisateur", nullable = false)
    private Utilisateurs utilisateur;
}
