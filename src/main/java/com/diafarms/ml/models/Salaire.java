package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Grille salariale d'un membre du personnel (Personnel, pas forcément un compte
// Utilisateurs — voir Personnel.java) — un seul Salaire par personnel (voir
// SalaireServiceImpl.definir, upsert). tauxBase a un sens différent selon
// modePaiement : montant fixe mensuel, taux par jour, ou taux par heure — voir
// SalaireServiceImpl.payer pour le calcul du montant réel à chaque paiement.
// L'historique des paiements réels vit dans PaiementSalaire, un par période payée.
@Entity
@Table(name = "salaires")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Salaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employe_id", nullable = false, unique = true)
    private Personnel employe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode_paiement", nullable = false, length = 20,
            columnDefinition = "varchar(20) not null default 'MENSUEL'")
    private ModePaiement modePaiement = ModePaiement.MENSUEL;

    // Sens dépendant de modePaiement : salaire mensuel fixe, taux journalier, ou
    // taux horaire (jamais un montant déjà multiplié par une durée).
    @Column(name = "taux_base", nullable = false)
    private Double tauxBase;

    @Embedded
    private Initialisation initialisation;

    public enum ModePaiement {
        MENSUEL, JOURNALIER, HORAIRE
    }
}
