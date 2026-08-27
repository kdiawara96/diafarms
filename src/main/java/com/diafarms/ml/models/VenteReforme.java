package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeVenteReforme;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Vente réforme — acte Finance, plafonnée par le total réformé (Reforme, Production)
// de TOUTE LA FERME moins déjà vendu (voir VenteReformeImpl.getStock). Répartie au
// prorata de l'effectif réformé disponible de chaque projet contributeur — voir
// VenteOeufs pour le détail du mécanisme.
@Entity
@Table(name = "ventes_reforme")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VenteReforme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    private LocalTime heure;

    @Column(name = "nombre_sujets", nullable = false)
    private Integer nombreSujets;

    // Informatif seulement — voir VenteOeufs.prixUnitaire.
    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    // Montant théorique — voir VenteOeufs.montant/montantRapporte pour le détail du
    // rapprochement avec ce que le vendeur a réellement rapporté.
    @Column(nullable = false)
    private Double montant;

    @Column(name = "montant_rapporte")
    private Double montantRapporte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    // Nullable pour compat avec les ventes créées avant l'introduction des magasins —
    // voir VenteOeufs.magasin pour le détail, même principe.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_id")
    private Magasin magasin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    // Client — optionnel, voir VenteOeufs.client pour le détail du routage de l'écart
    // théorique/rapporté vers SoldeClient plutôt que SoldeVendeur quand renseigné.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @OneToMany(mappedBy = "venteReforme", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VenteReformeRepartition> repartitions;

    // TETE (défaut) ou KILO — voir TypeVenteReforme. columnDefinition avec DEFAULT
    // explicite : indispensable pour que ddl-auto=update puisse ajouter cette colonne
    // NOT NULL sur la table déjà peuplée (même raison que VenteOeufs.typeOeuf).
    @Enumerated(EnumType.STRING)
    @Column(name = "type_vente", nullable = false, length = 10,
            columnDefinition = "varchar(10) not null default 'TETE'")
    private TypeVenteReforme typeVente = TypeVenteReforme.TETE;

    // Poids total pesé de la vente, en kg — renseigné uniquement si typeVente=KILO,
    // sert alors avec prixUnitaire (réinterprété comme prix/kg) à calculer montant
    // côté client. Jamais utilisé par la répartition entre projets (toujours par
    // nombreSujets).
    @Column(name = "poids_total_kg")
    private Double poidsTotalKg;

    @Embedded
    private Initialisation initialisation;
}
