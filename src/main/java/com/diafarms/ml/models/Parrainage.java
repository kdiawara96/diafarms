package com.diafarms.ml.models;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Parrainage d'une ferme (filleul) par une autre (parrain), saisi à l'inscription avec le
// code du parrain (Farm.codeParrainage). Une ferme n'est parrainée qu'UNE fois : contrainte
// d'unicité sur filleul_farm_id. La récompense (1 mois offert au parrain, +30 jours sur sa
// date de fin) est accordée au PREMIER paiement validé du filleul, une seule fois :
// recompenseLe est posé par un UPDATE ... WHERE recompense_le IS NULL (voir
// ParrainageService.recompenser), deux validations simultanées ne peuvent donc jamais
// offrir deux mois.
@Entity
@Table(name = "parrainages",
        uniqueConstraints = @UniqueConstraint(name = "uk_parrainage_filleul", columnNames = { "filleul_farm_id" }))
@Getter
@Setter
@NoArgsConstructor
public class Parrainage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parrain_farm_id", nullable = false)
    private Farm parrain;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "filleul_farm_id", nullable = false)
    private Farm filleul;

    // Code utilisé à l'inscription (copie : le code du parrain ne change pas, mais la
    // trace reste lisible).
    @Column(name = "code", nullable = false, length = 20)
    private String code;

    @Column(name = "cree_le", nullable = false)
    private LocalDateTime creeLe;

    // Récompense accordée (null = pas encore : le filleul n'a pas encore payé).
    @Column(name = "recompense_le")
    private LocalDateTime recompenseLe;

    @Column(name = "recompense_jours")
    private Integer recompenseJours;

    // Crédit prépayé : récompense en FCFA de crédit (null = ancienne récompense en jours).
    @Column(name = "recompense_credit")
    private Double recompenseCredit;

    // Date de fin du parrain avant et après la récompense (trace pour la console).
    @Column(name = "parrain_date_fin_avant")
    private LocalDate parrainDateFinAvant;

    @Column(name = "parrain_date_fin_apres")
    private LocalDate parrainDateFinApres;

    // Paiement du filleul qui a déclenché la récompense (id de paiements_abonnement).
    @Column(name = "paiement_id")
    private Long paiementId;
}
