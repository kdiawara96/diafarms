package com.diafarms.ml.models;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Périodes de suspension d'une ferme par l'équipe (console), pour le crédit prépayé : les
// jours suspendus ne sont jamais facturés, même pour un mois déjà passé au moment de la
// réactivation (voir CreditService.joursPayes). Une ligne par suspension : du = jour de la
// suspension (non facturé), au = veille de la réactivation (null tant qu'elle dure).
@Entity
@Table(name = "suspensions_credit", indexes = @Index(name = "ix_suspensions_credit_abonnement", columnList = "abonnement_id"))
@Getter
@Setter
@NoArgsConstructor
public class SuspensionCredit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "abonnement_id", nullable = false)
    private Long abonnementId;

    @Column(name = "du", nullable = false)
    private LocalDate du;

    @Column(name = "au")
    private LocalDate au;
}
