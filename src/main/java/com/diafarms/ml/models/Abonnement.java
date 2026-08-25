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
// dateFin + AbonnementConfig.dureeGraceHeures.
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

    @Embedded
    private Initialisation initialisation;
}
