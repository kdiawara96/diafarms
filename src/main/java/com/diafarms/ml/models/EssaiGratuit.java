package com.diafarms.ml.models;

import java.time.LocalDateTime;

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

// Mémoire des essais gratuits donnés : un essai par numéro de téléphone (et par e-mail)
// de propriétaire, même si le compte ou la ferme est supprimé ensuite (voir
// CreditService.essaiDejaUtilise). Téléphone normalisé en chiffres internationaux
// (Telephone.international), e-mail en minuscules. Une ligne par ferme inscrite.
@Entity
@Table(name = "essais_gratuits", indexes = {
        @Index(name = "ix_essais_gratuits_tel", columnList = "telephone"),
        @Index(name = "ix_essais_gratuits_email", columnList = "email") })
@Getter
@Setter
@NoArgsConstructor
public class EssaiGratuit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "farm_id")
    private Long farmId;

    @Column(name = "telephone", length = 30)
    private String telephone;

    @Column(name = "email", length = 100)
    private String email;

    // false : inscription sans essai (déjà utilisé), gardée pour la trace.
    @Column(name = "essai_donne")
    private Boolean essaiDonne;

    @Column(name = "cree_le", nullable = false)
    private LocalDateTime creeLe;
}
