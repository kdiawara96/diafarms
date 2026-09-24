package com.diafarms.ml.models;

import java.time.LocalDate;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Document de facturation généré depuis UNE vente (VenteOeufs/VenteReforme) ou UNE
// commande CONVERTIE — jamais anonyme (client obligatoire, décision utilisateur
// 2026-08-12, même choix que Commande) : contrairement à SoldeClient (qui suit la
// dette au fil des ventes), Facture est un snapshot figé au moment de l'émission
// (quantité/prix/montants ne bougent plus après, même si la vente d'origine est
// modifiée plus tard). montantPaye/statut peuvent évoluer via marquerPayee, qui
// enregistre un vrai paiement (Transaction + SoldeClient), même mécanisme que
// ClientServiceImpl.payerDette — voir FactureServiceImpl.
@Entity
@Table(name = "factures")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Facture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "numero_facture", nullable = false, unique = true, length = 30)
    private String numeroFacture;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Column(name = "date_emission", nullable = false)
    private LocalDate dateEmission;

    // D'où vient la facture — sourceUniqueId pointe vers VenteOeufs.uniqueId,
    // VenteReforme.uniqueId ou Commande.uniqueId selon sourceType (même principe que
    // Commande.venteUniqueId / Transaction.sourceUniqueId : pas de FK directe, plusieurs
    // entités cibles possibles).
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private SourceFacture sourceType;

    @Column(name = "source_unique_id", nullable = false, length = 50)
    private String sourceUniqueId;

    @Column(name = "description", nullable = false, length = 200)
    private String description;

    @Column(name = "quantite")
    private Integer quantite;

    @Column(name = "prix_unitaire")
    private Double prixUnitaire;

    // Snapshot figé au moment de l'émission — ne suit plus les modifications
    // ultérieures de la vente/commande d'origine.
    @Column(name = "montant_total", nullable = false)
    private Double montantTotal;

    // Non utilisé pour une facture non-legacy (montantPaye/statut recalculés à la volée
    // depuis les imputations des lignes — voir FactureServiceImpl.toDto/FactureDTO) :
    // reste à 0 et n'est jamais mis à jour après la génération. Seule une facture legacy
    // (voir `legacy` ci-dessous) affiche encore cette valeur telle quelle.
    @Column(name = "montant_paye", nullable = false, columnDefinition = "double precision not null default 0")
    private Double montantPaye = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'IMPAYEE'")
    private StatutFacture statut = StatutFacture.IMPAYEE;

    // Vrai pour les factures d'avant la refonte (source unique par vente, montantPaye
    // stocké manuellement par marquerPayee) : leur montantPaye stocké reste affiché tel
    // quel plutôt que recalculé depuis des lignes qu'elles n'ont pas. Mis à jour par la
    // reprise (Task 11) sur les factures existantes ; faux par défaut pour les nouvelles
    // factures générées par cette refonte, qui ont toujours des FactureLigne.
    @Column(nullable = false, columnDefinition = "boolean not null default false")
    private Boolean legacy = false;

    // Motif obligatoire (voir MotifSuppressionRequest) d'une annulation de facture —
    // voir FactureServiceImpl.annuler. Les ventes qu'elle listait redeviennent
    // facturables (FactureLigneRepo.venteDejaFacturee exclut les factures ANNULEE) ;
    // les paiements déjà encaissés dessus ne sont pas touchés.
    @Column(name = "motif_annulation", columnDefinition = "TEXT")
    private String motifAnnulation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    @Embedded
    private Initialisation initialisation;

    public enum SourceFacture {
        VENTE_OEUFS, VENTE_REFORME, COMMANDE, VENTES
    }

    public enum StatutFacture {
        IMPAYEE, PARTIELLE, PAYEE, ANNULEE
    }
}
