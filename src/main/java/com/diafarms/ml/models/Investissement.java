package com.diafarms.ml.models;

import java.time.LocalDate;
import java.util.List;

import com.diafarms.ml.commons.Initialisation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Enumerated;

import com.diafarms.ml.enums.TypeAffectation;

import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "investissements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Investissement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private String categorie; 

    @Column(nullable = false)
    private String nom;

    private String icon; 

    @Column(nullable = false)
    private Double montant; // En FCFA

    @Column(nullable = false)
    private LocalDate dateAchat;

    private String fournisseur;
    
    @Column(nullable = false)
    private String type; // Construction, Eau, Matériel, etc.

    @Column(nullable = false)
    private Integer dureeAmortissement; // En mois

    @Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private TypeAffectation affectation; // COMMUN ou DEDIE

    @Column(columnDefinition = "TEXT")
    private String commentaire;

    @Column(nullable = false)
    private Double amortiCumule = 0.0;

    @Embedded
    private Initialisation initialisation;

    // ============================================
    // RELATION AVEC UTILISATEUR (ManyToOne)
    // ============================================
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "id_utilisateur", nullable = false)
    private Utilisateurs utilisateur;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;
    
    // La liaison vers la table pivot de ventilation
    @OneToMany(mappedBy = "investissement", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InvestissementRepartition> repartitions;

    public Double getAmortissementMensuel() {
        if (this.montant == null || this.dureeAmortissement == null || this.dureeAmortissement == 0) return 0.0;
        return Math.round(this.montant / this.dureeAmortissement * 100.0) / 100.0;
    }

    public Double getValeurNette() {
        return this.montant - this.amortiCumule;
    }
}
