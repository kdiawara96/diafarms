package com.diafarms.ml.models;

import java.time.LocalDate;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Mouvement explicite de stock (œufs ou réforme) d'UN projet vers UN magasin —
// plafonné par le stock de ce projet pas encore transféré ailleurs, voir
// MagasinTransfertServiceImpl. C'est ce transfert (pas la vente) qui porte
// désormais l'attribution à un projet précis pour le chiffre d'affaires
// (voir VenteOeufsImpl/VenteReformeImpl.disponibleParProjetDansMagasin).
@Entity
@Table(name = "magasin_transferts")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class MagasinTransfert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_id", nullable = false)
    private MagasinVente magasin;

    // Toujours renseigné (OEUFS comme REFORME) : c'est ce qui porte l'attribution du
    // chiffre d'affaires à un projet précis en aval (VenteOeufsImpl/VenteReformeImpl).
    // Pour OEUFS, calculé automatiquement (répartition proportionnelle entre les
    // projets contributeurs DE batimentStockage ci-dessous, voir
    // MagasinTransfertServiceImpl.create) — l'utilisateur choisit un bâtiment, pas un
    // projet ; pour REFORME, toujours choisi directement par l'utilisateur (pas de
    // notion de bâtiment de stockage pour les sujets réformés).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    // Bâtiment de stockage SOURCE — renseigné uniquement pour un transfert OEUFS (null
    // pour REFORME). Voir CollecteOeufs.batimentStockage : c'est de là que les œufs
    // partent physiquement vers le magasin.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batiment_stockage_id")
    private Batiment batimentStockage;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TypeStockMagasin type;

    @Column(name = "quantite", nullable = false)
    private Integer quantite;

    @Column(name = "date", nullable = false)
    private LocalDate date;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    @Embedded
    private Initialisation initialisation;
}
