package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;

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
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "transactions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(nullable = false, unique = true, length = 20)
    private String ref; // ex: "TRX-001"

    @Column(nullable = false)
    private LocalDate date;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TypeTransaction type;

    // null = "Commun" (pas rattachée à un projet spécifique)
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id")
    private Projets projet;

    // Uniquement renseigné quand projet == null : projets que cette dépense/rentrée
    // commune concerne, pour le suivi par projet (simple rattachement, pas de
    // répartition de montant — contrairement à InvestissementRepartition).
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "transaction_projets_concernes",
            joinColumns = @JoinColumn(name = "transaction_id"),
            inverseJoinColumns = @JoinColumn(name = "projet_id")
    )
    private List<Projets> projetsConcernes = new ArrayList<>();

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false)
    private Double montant;

    @Column(nullable = false)
    private String categorie;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatutTransaction statut = StatutTransaction.EN_ATTENTE;

    @Column(columnDefinition = "TEXT")
    private String commentaireRejet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "validateur_id")
    private Utilisateurs validateur;

    private LocalDateTime dateValidation;

    // Demande de suppression (pas une suppression directe) — voir
    // TransactionServiceImpl.demanderSuppression/confirmerSuppression. Non null =
    // une suppression est en attente de validation par un admin/responsable, même
    // principe que statut/validateur mais sur une dimension différente (une
    // transaction déjà VALIDE peut avoir une suppression en attente). removed=true
    // une fois confirmée (Initialisation), ces deux champs restent renseignés pour
    // la traçabilité — jamais remis à null après confirmation, seulement après un
    // refus (la demande est alors annulée).
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "demande_suppression_par_id")
    private Utilisateurs demandeSuppressionPar;

    private LocalDateTime dateDemandeSuppression;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    // Provenance de la transaction : MANUEL (saisie libre, formulaire Transaction
    // classique) ou générée automatiquement par une vente (VenteOeufs/VenteReforme,
    // via TransactionService.createFromSource) — remplace le repérage fragile par
    // regex sur `description` ("Vente réforme - lot de N sujets") utilisé côté front
    // avant cette entité. sourceUniqueId pointe vers VenteOeufsRepartition.uniqueId ou
    // VenteReformeRepartition.uniqueId selon sourceType (jamais VenteOeufs/VenteReforme
    // directement) — une vente farm-wide devient une Transaction PAR PROJET
    // contributeur, sourceUniqueId identifie donc la LIGNE de répartition précise, pas
    // la vente entière (voir VenteOeufsImpl.repartirEtCreerTransactions).
    // columnDefinition avec DEFAULT explicite : indispensable pour que ddl-auto=update
    // puisse ajouter cette colonne NOT NULL sur la table `transactions` existante (déjà
    // peuplée) — sans DEFAULT, Postgres refuse l'ALTER TABLE ADD COLUMN ... NOT NULL.
    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'MANUEL'")
    private SourceTransaction sourceType = SourceTransaction.MANUEL;

    @Column(name = "source_unique_id", length = 50)
    private String sourceUniqueId;

    // Qui a initié cette transaction (saisie manuelle ou vente œufs/réforme dont elle
    // découle) — distinct de `validateur`, qui est qui a VALIDÉ/REJETÉ, pas qui a créé.
    // Nullable : les transactions déjà en base avant ce champ n'ont personne à y
    // mettre. Sert à restreindre la page Ventes à ses propres ventes pour un
    // FINANCIER (voir TransactionServiceImpl.resolveVendeurScopeForList), alors que
    // Comptabilité restreint par projet (responsableFinance) — deux axes différents.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    // Client concerné, si pertinent (typiquement une transaction "Remboursement client",
    // voir ClientServiceImpl.payerDette) — nullable, la plupart des transactions n'ont
    // pas de client (dépenses, ventes directes...). Distinct du client d'une VenteOeufs/
    // VenteReforme (voir TransactionDTO.clientNom pour ces transactions-là, dérivé via
    // TransactionServiceImpl.enrichMontantReel plutôt que stocké ici) : ce champ sert
    // aux transactions non issues d'une vente qui concernent quand même un client.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id")
    private Client client;

    @Embedded
    private Initialisation initialisation;
}
