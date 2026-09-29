package com.diafarms.ml.models;

import java.time.LocalDate;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.FormeMedicament;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Achat de médicament ou de vaccin pour un projet (Sortie d'argent, catégorie Santé /
// Vétérinaire, « Achat de médicament ») : une seule saisie fait la dépense (transaction
// générée, source MEDICAMENT) ET l'entrée en stock du projet. Les soins de Production
// peuvent ensuite puiser dans ce stock (Soins.depuisStock), par produit et unité.
@Entity
@Table(name = "achats_medicament")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class AchatMedicament {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "nom", nullable = false, length = 150)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(name = "forme", length = 20)
    private FormeMedicament forme;

    // flacon, litre, ml, sachet, kg, g, boîte, comprimé, dose...
    @Column(name = "unite", nullable = false, length = 30)
    private String unite;

    @Column(name = "quantite", nullable = false)
    private Double quantite;

    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    @Column(name = "cout_total", nullable = false)
    private Double coutTotal;

    @Column(name = "date_achat", nullable = false)
    private LocalDate dateAchat;

    @Column(name = "fournisseur", length = 100)
    private String fournisseur;

    @Column(name = "observations", length = 500)
    private String observations;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    // Facultatif : poulailler visé (le projet reste la référence du stock).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batiment_id")
    private Batiment batiment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
