package com.diafarms.ml.models;

import java.time.LocalDate;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.enums.TypeVenteReforme;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Ce qu'un client demande AVANT que la vente ne soit finalisée (quantité pas encore
// livrée, paiement pas encore intégralement encaissé, éventuellement un acompte) —
// distincte d'une vente immédiate (VenteOeufs/VenteReforme). Une commande a toujours
// un client (contrairement à une vente, où c'est optionnel) : une commande anonyme
// n'a pas de sens, on ne peut pas prévenir quelqu'un qu'on ne connaît pas quand c'est
// prêt. Convertie en vente via CommandeServiceImpl.convertirEnVente, qui réutilise
// directement VenteOeufsService/VenteReformeService.create (même logique de
// répartition entre projets contributeurs, aucune duplication).
@Entity
@Table(name = "commandes")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Commande {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_id", nullable = false)
    private Magasin magasin;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TypeStockMagasin type;

    @Column(name = "quantite", nullable = false)
    private Integer quantite;

    // Cumul déjà livré (via CommandeServiceImpl.livrer, appelée une ou plusieurs fois
    // pour une grosse commande livrée au fil de la collecte) — NULL sur les commandes
    // existantes avant ce champ, traité comme 0. Passe à CONVERTIE seulement une fois
    // quantiteLivree >= quantite ; reste CONFIRMEE/EN_ATTENTE entre deux livraisons
    // partielles.
    @Column(name = "quantite_livree")
    private Integer quantiteLivree;

    @Column(name = "prix_unitaire_estime")
    private Double prixUnitaireEstime;

    @Column(name = "montant_estime", nullable = false)
    private Double montantEstime;

    // Tarification d'une commande de RÉFORME : TETE (défaut, prix par sujet) ou KILO
    // (prix au kilo, montant fixé à chaque livraison sur le poids réellement pesé, voir
    // CommandeServiceImpl.livrer). Toujours TETE pour une commande d'œufs. La quantité
    // (commandée, livrée, restante) reste TOUJOURS en sujets : on vend des sujets vivants
    // entiers, le poids ne sert qu'à fixer le prix. columnDefinition avec DEFAULT :
    // indispensable pour que ddl-auto=update ajoute la colonne NOT NULL sur la table déjà
    // peuplée (même principe que VenteReforme.typeVente).
    @Enumerated(EnumType.STRING)
    @Column(name = "tarification", nullable = false, length = 10,
            columnDefinition = "varchar(10) not null default 'TETE'")
    private TypeVenteReforme tarification = TypeVenteReforme.TETE;

    // KILO seulement : prix au kilo convenu à la commande (prix par défaut des
    // livraisons, surchargeable à chaque livraison) et poids total estimé (optionnel,
    // sert à calculer montantEstime = poidsEstimeKg x prixKgEstime).
    @Column(name = "prix_kg_estime")
    private Double prixKgEstime;

    @Column(name = "poids_estime_kg")
    private Double poidsEstimeKg;

    // Acompte versé à la commande (optionnel) — encaissé et porté au solde du client
    // dès la création (voir CommandeServiceImpl.create/payerDette), PAS reporté à
    // nouveau sur la vente générée lors de la conversion (ça le compterait deux fois
    // sur le solde, voir CommandeServiceImpl.convertirEnVente).
    @Column(name = "montant_acompte")
    private Double montantAcompte;

    @Column(name = "date_commande", nullable = false)
    private LocalDate dateCommande;

    @Column(name = "date_livraison_prevue")
    private LocalDate dateLivraisonPrevue;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'EN_ATTENTE'")
    private StatutCommande statut = StatutCommande.EN_ATTENTE;

    // Renseigné uniquement une fois CONVERTIE — pointe vers VenteOeufs.uniqueId ou
    // VenteReforme.uniqueId selon `type` (jamais les deux, pas de FK directe : deux
    // entités cible possibles selon le type, même principe que Transaction.sourceUniqueId).
    @Column(name = "vente_unique_id", length = 50)
    private String venteUniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    // Motif de clôture (livrée en partie, on arrête là) ou d'annulation (rien livré) —
    // voir CommandeServiceImpl.cloturer/annuler. Obligatoire (MotifSuppressionRequest),
    // mais nullable en base : vide tant que la commande suit son cours normal.
    @Column(name = "motif_fin", columnDefinition = "TEXT")
    private String motifFin;

    @Embedded
    private Initialisation initialisation;

    public enum StatutCommande {
        EN_ATTENTE, CONFIRMEE, EN_LIVRAISON, CONVERTIE, CLOTUREE, ANNULEE
    }
}
