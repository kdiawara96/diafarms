package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.ModePaiement;
import com.diafarms.ml.enums.StatutMouvement;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Argent rendu au client, pris sur son avance (des imputations de cible REMBOURSEMENT
// consomment les paiements). Transaction "Remboursement au client" (REMBOURSEMENT_CLI).
@Entity
@Table(name = "remboursements_client")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RemboursementClient {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "commande_id")
    private Commande commande;

    @Column(nullable = false)
    private LocalDate date;

    @Column(nullable = false)
    private Double montant;

    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private ModePaiement mode;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String motif;

    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "effectue_par_id")
    private Utilisateurs effectuePar;

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
