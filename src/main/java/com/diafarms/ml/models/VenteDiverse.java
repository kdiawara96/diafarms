package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.ProduitVenteDiverse;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

// Vente de fientes ou "autre vente" — acte Finance, sans stock suivi, rattachée au
// Projet (projet non null) ou à toute la ferme (projet null, cas de toutes les anciennes). Avant, ces ventes n'étaient qu'une Transaction manuelle
// (catégorie "Vente fientes"/"Autre vente") : la page Ventes devait alors les
// reconstruire depuis la comptabilité. Maintenant la vente est l'enregistrement
// d'origine, et sa Transaction (SourceTransaction.VENTE_DIVERSE, sourceUniqueId =
// uniqueId) n'en est que la conséquence financière, créée/modifiée/supprimée avec elle.
@Entity
@Table(name = "ventes_diverses")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VenteDiverse {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ProduitVenteDiverse produit;

    // Nombre de sacs pour des fientes, facultatif (null = non précisé).
    private Double quantite;

    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    @Column(nullable = false)
    private Double montant;

    @Column(columnDefinition = "TEXT")
    private String description;

    // « Cette vente concerne » : le Projet (sa transaction compte alors dans le résultat du
    // projet) ou, null, toute la ferme. Colonne nullable ajoutée par ddl-auto.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id")
    private Projets projet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    // Suppression en deux temps, comme VenteOeufs/VenteReforme : non null = en attente.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demande_suppression_par_id")
    private Utilisateurs demandeSuppressionPar;

    private LocalDateTime dateDemandeSuppression;

    @Column(name = "motif_suppression", columnDefinition = "TEXT")
    private String motifSuppression;

    @Embedded
    private Initialisation initialisation;
}
