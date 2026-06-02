package com.diafarms.ml.models;


import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

import com.diafarms.ml.commons.Initialisation;

@Entity
@Table(name = "alimentations")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Alimentation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId; // ex: "ALI-001"

    @Column(name = "nom_aliment", nullable = false, length = 100)
    private String nomAliment; // ex: "Aliment Démarrage Poulettes", "Finisseur"

    @Column(name = "sac", nullable = false)
    private Double sac; // Quantité distribuée ou achetée en sacs (ex: 2.5 sacs)

    @Column(name = "quantite_kg", nullable = false)
    private Double quantiteKg; // Quantité distribuée ou achetée en kg (

    @Column(name = "cout_total", nullable = false)
    private Double coutTotal; // Coût de cette alimentation pour le suivi financier

    @Column(name = "date_distribution", nullable = false)
    private LocalDate dateDistribution; // Date à laquelle l'aliment a été donné/acheté

    @Column(name = "observations", length = 500)
    private String observations; // Pour noter un changement de fournisseur, un refus de d'aliment, etc.

    @Embedded
    private Initialisation initialisation;

    // --- LE LIEN AVEC LE PROJET ---
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    // Lien avec la ferme pour la sécurité des données multi-locataires
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;
}
