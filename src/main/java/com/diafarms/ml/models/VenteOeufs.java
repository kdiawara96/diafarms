package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import com.diafarms.ml.commons.Initialisation;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
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

// Vente d'œufs — acte Finance, plafonnée par le total collecté (CollecteOeufs,
// Production) de TOUTE LA FERME moins ce qui a déjà été vendu (voir
// VenteOeufsImpl.getStock). N'est pas rattachée à UN projet : elle est répartie au
// prorata du stock disponible de chaque projet contributeur (voir repartitions,
// VenteOeufsRepartition) — chaque part génère sa propre Transaction "entrée"
// directement liée à son projet, pour que le chiffre d'affaires par projet reste exact.
@Entity
@Table(name = "ventes_oeufs")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VenteOeufs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false)
    private LocalDate date;

    private LocalTime heure;

    @Column(name = "quantite_oeufs", nullable = false)
    private Integer quantiteOeufs;

    // Informatif seulement (moyenne/négociation) : le montant réellement encaissé
    // est celui de la Transaction générée, jamais recalculé depuis prixUnitaire.
    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    // Montant théorique (quantité × prix, ou saisi librement) — voir montantRapporte
    // ci-dessous pour le montant réellement encaissé par le vendeur ce jour-là.
    @Column(nullable = false)
    private Double montant;

    // Ce que le vendeur a réellement rapporté ce jour-là — peut différer de `montant`
    // (dette si inférieur, remboursement d'une dette précédente si supérieur). Nullable :
    // une vente créée par un ADMIN/RESPONSABLE (pas un vendeur terrain) n'a pas
    // toujours cette distinction. Voir SoldeVendeur pour le solde cumulé qui en découle.
    @Column(name = "montant_rapporte")
    private Double montantRapporte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    // Nullable pour compat avec les ventes créées avant l'introduction des magasins
    // (stock farm-wide à l'époque) — obligatoire pour toute nouvelle vente, voir
    // VenteOeufsImpl.create(). Le plafond de stock et la répartition entre projets
    // se calculent désormais à l'intérieur de CE magasin, pas farm-wide.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_id")
    private Magasin magasin;

    // Vendeur — sert à imputer l'écart théorique/rapporté à SON solde (SoldeVendeur),
    // et au filtre "mes ventes" (comme Transaction.creePar sur la Transaction générée).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    // Client — optionnel ("vente directe" sans client identifié toujours possible).
    // Quand renseigné, l'écart théorique/rapporté est imputé à SON solde (SoldeClient)
    // plutôt qu'à celui du vendeur (SoldeVendeur) : ce n'est pas le vendeur qui est en
    // tort, c'est une vente à crédit pas encore intégralement payée par ce client. Voir
    // VenteOeufsImpl.create/update pour le routage exact.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @OneToMany(mappedBy = "venteOeufs", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<VenteOeufsRepartition> repartitions;

    @Embedded
    private Initialisation initialisation;
}
