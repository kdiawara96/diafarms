package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.OriginePaiement;
import com.diafarms.ml.enums.StatutMouvement;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Argent donné par un client. Jamais modifié : une erreur s'annule (motif) et on en
// saisit un nouveau. Sa Transaction "Paiement client" (source PAIEMENT_CLIENT) le suit.
@Entity
@Table(name = "paiements_client")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PaiementClient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Double montant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ModePaiement mode;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private OriginePaiement origine;

    // Facultatifs : priorité d'imputation et traçabilité.
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "commande_id")
    private Commande commande;

    @Enumerated(EnumType.STRING) @Column(name = "vente_cible_type", length = 20)
    private CibleImputation venteCibleType;

    @Column(name = "vente_cible_unique_id", length = 50)
    private String venteCibleUniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "facture_id")
    private Facture facture;

    @Column(columnDefinition = "TEXT")
    private String observations;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "recu_par_id")
    private Utilisateurs recuPar;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10)
    private StatutMouvement statut = StatutMouvement.ACTIF;

    @Column(name = "motif_annulation", columnDefinition = "TEXT")
    private String motifAnnulation;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "annule_par_id")
    private Utilisateurs annulePar;

    private LocalDateTime dateAnnulation;

    @Embedded
    private Initialisation initialisation;
}
