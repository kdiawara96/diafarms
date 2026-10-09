package com.diafarms.ml.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Réglages tarifaires de la plateforme, une seule ligne (voir
// AbonnementServiceImpl.getOuCreerConfig, créée avec des valeurs par défaut au
// premier accès si absente) — modifiable par le SUPER_ADMIN, jamais codé en dur.
@Entity
@Table(name = "abonnement_config")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AbonnementConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "prix_mensuel", nullable = false)
    private Double prixMensuel;

    @Column(name = "prix_annuel", nullable = false)
    private Double prixAnnuel;

    @Column(name = "duree_essai_jours", nullable = false)
    private Integer dureeEssaiJours;

    // Ancien délai de grâce en HEURES : plus utilisé pour le calcul (remplacé par
    // delaiGraceJours ci-dessous), gardé car la colonne existe déjà en base en NOT NULL.
    @Column(name = "duree_grace_heures", nullable = false)
    private Integer dureeGraceHeures;

    // Délai de grâce en JOURS après la date de fin (abonnement ou essai) : la ferme garde
    // tout son accès pendant ces jours-là. Nullable (colonne ajoutée par ddl-auto sur une
    // table existante) : null = AbonnementEcheance.DELAI_GRACE_JOURS_DEFAUT (5).
    @Column(name = "delai_grace_jours")
    private Integer delaiGraceJours;

    // Prix par poule (décision du 2026-10-05, voir commons/AbonnementTarif). Colonnes
    // nullables ajoutées par ddl-auto : null = valeur par défaut (6 FCFA par poule,
    // minimum 5 000 FCFA par mois, 2 mois offerts sur l'année, arrondi à 100 FCFA).
    // prixMensuel/prixAnnuel ci-dessus : ancien tarif fixe, plus utilisé pour les fermes.
    @Column(name = "prix_par_poule")
    private Double prixParPoule;

    @Column(name = "prix_minimum_mensuel")
    private Double prixMinimumMensuel;

    @Column(name = "mois_offerts_annuel")
    private Integer moisOffertsAnnuel;

    @Column(name = "arrondi_prix")
    private Integer arrondi;

    // Crédit prépayé (voir commons/AbonnementCredit). Nullables, null = valeur par défaut :
    // bonus de 20 % pour une recharge de 50 000 FCFA ou plus (remplace les « mois offerts
    // sur l'an »), tarif sur devis au-delà de 10 000 poules, 5 000 FCFA de crédit au parrain.
    @Column(name = "bonus_seuil")
    private Double bonusSeuil;

    @Column(name = "bonus_pourcent")
    private Double bonusPourcent;

    @Column(name = "seuil_sur_devis")
    private Integer seuilSurDevis;

    @Column(name = "credit_parrainage")
    private Double creditParrainage;
}
