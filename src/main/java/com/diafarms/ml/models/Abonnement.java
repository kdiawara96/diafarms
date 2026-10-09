package com.diafarms.ml.models;

import java.time.LocalDate;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.Periodicite;
import com.diafarms.ml.enums.StatutAbonnement;

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
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// État courant de l'abonnement d'UNE ferme (une seule ligne par Farm, voir
// AbonnementServiceImpl.creerEssaiPourFarm/getOuCreerAbonnement) — l'historique des
// paiements déclarés/validés vit dans PaiementAbonnement, un par déclaration. Le
// champ statut est mis à jour à chaque validation de paiement mais n'est jamais lu
// directement pour décider d'un blocage : voir
// AbonnementServiceImpl.calculerStatutEffectif, toujours recalculé à partir de
// dateFin + AbonnementConfig.delaiGraceJours (voir AbonnementEcheance).
@Entity
@Table(name = "abonnements")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Abonnement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false, unique = true)
    private Farm farm;

    @Enumerated(EnumType.STRING)
    @Column(name = "statut", nullable = false, length = 20)
    private StatutAbonnement statut;

    @Column(name = "date_debut", nullable = false)
    private LocalDate dateDebut;

    @Column(name = "date_fin", nullable = false)
    private LocalDate dateFin;

    // Nullable tant qu'aucun paiement n'a jamais été validé (pendant l'essai
    // initial) — renseignée à la première validation, voir
    // AbonnementServiceImpl.valider.
    @Enumerated(EnumType.STRING)
    @Column(name = "periodicite", length = 20)
    private Periodicite periodicite;

    // Suspension manuelle par le SUPER_ADMIN (console d'administration) : bloque le web
    // de la ferme tout de suite, quelle que soit dateFin, jusqu'à la réactivation.
    // Colonnes nullables (ajoutées par ddl-auto sur une table existante) : null = non
    // suspendu. Volontairement PAS une nouvelle valeur de StatutAbonnement (elle exigerait
    // de modifier à la main la contrainte CHECK Postgres abonnements_statut_check).
    // Le statut effectif exposé reste EXPIRE pour les anciens clients, avec suspendu=true
    // (voir AbonnementEcheance).
    @Column(name = "suspendu")
    private Boolean suspendu;

    @Column(name = "motif_suspension", length = 500)
    private String motifSuspension;

    @Column(name = "suspendu_le")
    private java.time.LocalDateTime suspenduLe;

    // Tarif spécial (prix fixe par mois) décidé par le SUPER_ADMIN depuis la console, par
    // exemple pour garder un premier client à son prix : remplace la règle par poule (voir
    // commons/AbonnementTarif). Colonnes nullables : null = prix par poule normal.
    @Column(name = "prix_mensuel_fixe")
    private Double prixMensuelFixe;

    @Column(name = "motif_prix_fixe", length = 300)
    private String motifPrixFixe;

    @Column(name = "prix_fixe_le")
    private java.time.LocalDateTime prixFixeLe;

    // Modèle du crédit prépayé (décision du 2026-10-09, voir CreditService et
    // commons/AbonnementCredit). Colonnes nullables ajoutées par ddl-auto :
    //   creditDepuis   : premier jour payé par le crédit (lendemain de la fin de l'essai,
    //                    ou de la fin de la période déjà payée pour une ferme d'avant le
    //                    crédit). null = ferme pas encore passée au crédit (voir
    //                    CreditService.convertirAnciennesFermes).
    //   creditEpuiseLe : premier jour NON couvert quand le crédit est à zéro ou en dessous
    //                    (null tant que le crédit est positif). dateFin = ce jour - 1 : les
    //                    rappels, la grâce et le blocage existants s'appliquent tels quels.
    //   essaiRefuse    : pas d'essai gratuit (téléphone ou e-mail du propriétaire déjà
    //                    utilisé par une autre ferme), voir CreditService.essaiDejaUtilise.
    // Le solde lui-même n'est pas stocké ici : c'est la somme des MouvementCredit.
    @Column(name = "credit_depuis")
    private LocalDate creditDepuis;

    @Column(name = "credit_epuise_le")
    private LocalDate creditEpuiseLe;

    @Column(name = "essai_refuse")
    private Boolean essaiRefuse;

    @Embedded
    private Initialisation initialisation;

    public boolean estEnCredit() {
        return creditDepuis != null;
    }

    public boolean estSuspendu() {
        return Boolean.TRUE.equals(suspendu);
    }
}
