package com.diafarms.ml.models;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne du compte de crédit d'une ferme (crédit prépayé, voir CreditService) :
// recharge, bonus, mensualité, parrainage ou ajustement. Le solde d'une ferme est la
// somme des montants (positif = crédit ajouté, négatif = crédit retiré). Jamais modifiée
// ni supprimée : une erreur se corrige par un ajustement.
//
// type : texte simple (pas d'enum) pour ne jamais dépendre d'une contrainte CHECK
// Postgres à retoucher à la main (voir CreditService.TYPE_*).
// cle : unique, garantit qu'un mouvement automatique n'est écrit qu'une fois
// (« MENS:<abonnement>:2026-09 », « RECH:<paiement> », « BONUS:<paiement> »,
// « PARR:<parrainage> ») ; null pour un ajustement.
@Entity
@Table(name = "mouvements_credit", indexes = @Index(name = "ix_mouvements_credit_abonnement", columnList = "abonnement_id, date_mouvement"))
@Getter
@Setter
@NoArgsConstructor
public class MouvementCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "abonnement_id", nullable = false)
    private Abonnement abonnement;

    @Column(name = "type", nullable = false, length = 20)
    private String type;

    // FCFA, toujours (jamais la devise de la ferme).
    @Column(name = "montant", nullable = false)
    private Double montant;

    @Column(name = "solde_apres", nullable = false)
    private Double soldeApres;

    @Column(name = "date_mouvement", nullable = false)
    private LocalDateTime dateMouvement;

    @Column(name = "cle", unique = true, length = 80)
    private String cle;

    // Mensualité : mois payé (« 2026-09 »), moyenne des poules vivantes, jours comptés
    // (du premier jour payé à la fin du mois) sur les jours du mois, tarif spécial ou non.
    @Column(name = "mois", length = 7)
    private String mois;

    @Column(name = "poules_moyenne")
    private Double poulesMoyenne;

    @Column(name = "jours")
    private Integer jours;

    @Column(name = "jours_mois")
    private Integer joursMois;

    @Column(name = "prix_fixe")
    private Boolean prixFixe;

    // Recharge / bonus : paiement d'origine (paiements_abonnement.id) ; parrainage : id.
    @Column(name = "paiement_id")
    private Long paiementId;

    @Column(name = "parrainage_id")
    private Long parrainageId;

    // Texte lisible (« Septembre 2026 : 1 000 poules en moyenne sur 30 jours »), raison
    // d'un ajustement.
    @Column(name = "libelle", length = 500)
    private String libelle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "auteur_id")
    private Utilisateurs auteur;
}
