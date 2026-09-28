package com.diafarms.ml.models;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
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

    // Poulaillers concernés par cet investissement (ex : construction d'un bâtiment),
    // lien facultatif 0..N dans la table investissement_batiments (créée par
    // ddl-auto, aucune donnée existante touchée). Pur lien de consultation : il ne
    // change ni les répartitions ni l'amortissement des projets. Supprimer
    // l'investissement ne retire que les lignes de liaison (jamais le poulailler) ;
    // supprimer un poulailler retire aussi ses liens (BatimentImpl.deleteOrRecover).
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "investissement_batiments",
            joinColumns = @JoinColumn(name = "investissement_id"),
            inverseJoinColumns = @JoinColumn(name = "batiment_id"))
    private Set<Batiment> batiments = new HashSet<>();

    public Double getAmortissementMensuel() {
        if (this.montant == null || this.dureeAmortissement == null || this.dureeAmortissement == 0) return 0.0;
        return Math.round(this.montant / this.dureeAmortissement * 100.0) / 100.0;
    }

    public Double getValeurNette() {
        return this.montant - this.amortiCumule;
    }
}
