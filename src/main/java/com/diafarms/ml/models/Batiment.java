package com.diafarms.ml.models;

import java.time.LocalDate;
import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "batiments")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Batiment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;  

    @Column(name = "nom", nullable = false, length = 100)
    private String nom;

    @Column(name = "capacite", nullable = false)
    private Integer capacite;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TypeBatiment type;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutBatiment statut = StatutBatiment.DISPONIBLE;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "date_derniere_maintenance")
    private LocalDate dateDerniereMaintenance;

    @Column(name = "superficie_m2")
    private Double superficieM2;


    @Embedded
    private Initialisation initialisation;


    // Enumération pour le type de bâtiment
    public enum TypeBatiment {
        POULAILLER("poulailler"),
        STOCKAGE("stockage"),
        AUTRE("autre");

        private final String value;

        TypeBatiment(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // Enumération pour le statut du bâtiment
    public enum StatutBatiment {
        DISPONIBLE("disponible"),
        OCCUPE("occupe"),
        MAINTENANCE("maintenance");

        private final String value;

        StatutBatiment(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    // Méthodes utilitaires
    public boolean estDisponible() {
        return this.statut == StatutBatiment.DISPONIBLE;
    }

    public boolean estOccupe() {
        return this.statut == StatutBatiment.OCCUPE;
    }

    public boolean estEnMaintenance() {
        return this.statut == StatutBatiment.MAINTENANCE;
    }

    // public void affecterProjet(String projetId) {
    //     this.projetAffecte = projetId;
    //     this.statut = StatutBatiment.OCCUPE;
    // }

    // public void liberer() {
    //     this.projetAffecte = null;
    //     this.statut = StatutBatiment.DISPONIBLE;
    // }

    public void mettreEnMaintenance() {
        this.statut = StatutBatiment.MAINTENANCE;
        this.dateDerniereMaintenance = LocalDate.now();
    }
}
