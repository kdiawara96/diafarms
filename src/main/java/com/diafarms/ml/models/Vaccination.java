package com.diafarms.ml.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

import com.diafarms.ml.commons.Initialisation;

@Entity
@Table(name = "vaccinations")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Vaccination {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId; // ex: "VAC-001"

    @Column(name = "nom_vaccin", nullable = false, length = 100)
    private String nomVaccin; // ex: "Gumboro", "Newcastle", "Bronchite Infectieuse"

    @Column(name = "date_vaccination", nullable = false)
    private LocalDate dateVaccination;

    @Column(name = "quantite", nullable = false)
    private Integer quantite; // Souvent exprimé en nombre de doses (ex: 1000 doses) ou flacons

    @Column(name = "prix_unitaire", nullable = false)
    private Double prixUnitaire;

    @Column(name = "cout_total")
    private Double coutTotal; // Quantité * Prix Unitaire (calculé ou stocké)

    @Column(name = "mode_administration", length = 50)
    private String modeAdministration; // Optionnel : ex: "Dans l'eau de boisson", "Injection"

    @Embedded
    private Initialisation initialisation;

    // --- LE LIEN AVEC LE PROJET ---
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    // En suivant notre logique précédente, on garde le farm_id direct pour la sécurité/performance
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    // Optionnel : Calculer automatiquement le coût total avant l'insertion en BDD
    @PrePersist
    @PreUpdate
    public void calculerCoutTotal() {
        if (this.quantite != null && this.prixUnitaire != null) {
            this.coutTotal = this.quantite * this.prixUnitaire;
        }
    }
}