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
    private Magasin magasin;

    // Toujours renseigné (OEUFS comme REFORME) : c'est ce qui porte l'attribution du
    // chiffre d'affaires à un projet précis en aval (VenteOeufsImpl/VenteReformeImpl).
    // Calculé automatiquement (répartition proportionnelle entre les projets
    // contributeurs DE magasinStockage ci-dessous, voir MagasinTransfertServiceImpl.create) :
    // l'utilisateur choisit un magasin de stockage, pas un projet. Seul cas choisi
    // directement : réformés anciens sans magasin de stockage (voir ReformeStockage).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    // Magasin de stockage SOURCE (Magasin.TypeMagasin.STOCKAGE), pour les œufs comme pour
    // les réformés (voir CollecteOeufs.magasinStockage, Reforme.magasinStockage) : c'est de
    // là qu'ils partent vers le point de vente ci-dessus. Null : transfert REFORME ancien
    // (« depuis le projet » ou envoi direct du 2 octobre 2026).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_stockage_id")
    private Magasin magasinStockage;

    // Réforme à l'origine de ce transfert (transfert REFORME automatique, voir
    // ReformeStockage) : la modification, la suppression ou la restauration de la
    // réforme ajuste ce transfert. Null = transfert manuel ou transfert d'œufs.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reforme_id")
    private Reforme reforme;

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
