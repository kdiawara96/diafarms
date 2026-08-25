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

    @Column(name = "duree_grace_heures", nullable = false)
    private Integer dureeGraceHeures;
}
