package com.diafarms.ml.models;

import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutPaiementAbonnement;

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
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une déclaration de paiement ("J'ai payé") pour un Abonnement — historique complet,
// plusieurs lignes par ferme au fil du temps (même patron que
// Salaire/PaiementSalaire : Abonnement = état courant, PaiementAbonnement =
// historique des mouvements). Voir AbonnementServiceImpl.declarerPaiement/valider/
// rejeter.
@Entity
@Table(name = "paiements_abonnement")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PaiementAbonnement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "abonnement_id", nullable = false)
    private Abonnement abonnement;

    @Column(name = "montant", nullable = false)
    private Double montant;

    // Périodicité que CE paiement couvre (peut différer de celle actuellement sur
    // Abonnement si la ferme change de formule au renouvellement).
    @Enumerated(EnumType.STRING)
    @Column(name = "periodicite", nullable = false, length = 20)
    private Periodicite periodicite;

    @Column(name = "moyen_paiement", nullable = false, length = 50)
    private String moyenPaiement;

    @Column(name = "reference", length = 100)
    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutPaiementAbonnement statut;

    @Column(name = "date_declaration", nullable = false)
    private LocalDateTime dateDeclaration;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "declare_par_id")
    private Utilisateurs declarePar;

    @Column(name = "date_validation")
    private LocalDateTime dateValidation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "valide_par_id")
    private Utilisateurs validePar;

    @Column(name = "motif_rejet", columnDefinition = "TEXT")
    private String motifRejet;

    // true : paiement reçu en dehors de l'application (espèces, virement...) et
    // enregistré directement par le SUPER_ADMIN depuis la console (« Activer /
    // prolonger »), créé déjà VALIDE. Nullable (colonne ajoutée par ddl-auto) : null =
    // paiement déclaré par la ferme avec « J'ai payé ».
    @Column(name = "hors_application")
    private Boolean horsApplication;

    // Montant attendu au moment de la déclaration (tarif de la ferme ce jour-là, voir
    // AbonnementTarifService) et poules comptées utilisées pour ce calcul : l'équipe voit
    // ce que la ferme devait payer. Nullables : null pour les paiements d'avant le prix
    // par poule et pour ceux saisis par le SUPER_ADMIN.
    @Column(name = "montant_attendu")
    private Double montantAttendu;

    @Column(name = "poules_comptees")
    private Integer poulesComptees;

    @Embedded
    private Initialisation initialisation;
}
