package com.diafarms.ml.models;

import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.StatutMouvement;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// "Tel paiement règle telle vente (ou tel remboursement) pour tel montant". Créée par
// CompteClientService.imputer, jamais modifiée : annulée puis recréée si besoin.
@Entity
@Table(name = "imputations_paiement", indexes = {
        @Index(name = "idx_imputation_cible", columnList = "cible_type,cible_unique_id"),
        @Index(name = "idx_imputation_paiement", columnList = "paiement_id")})
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class ImputationPaiement {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "paiement_id", nullable = false)
    private PaiementClient paiement;

    @Enumerated(EnumType.STRING) @Column(name = "cible_type", nullable = false, length = 20)
    private CibleImputation cibleType;

    @Column(name = "cible_unique_id", nullable = false, length = 50)
    private String cibleUniqueId;

    @Column(nullable = false)
    private Double montant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private StatutMouvement statut = StatutMouvement.ACTIF;

    @Column(name = "motif_annulation", columnDefinition = "TEXT")
    private String motifAnnulation;

    private LocalDateTime dateAnnulation;

    @Embedded
    private Initialisation initialisation;
}
