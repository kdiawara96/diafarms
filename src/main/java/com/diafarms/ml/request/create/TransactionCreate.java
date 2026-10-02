package com.diafarms.ml.request.create;

import java.time.LocalDate;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionCreate {
    private String type; // "ENTREE" | "SORTIE"
    // Nouveau format (web, APK >= 1.36) : « Cette dépense concerne » = PROJET | SITE | FERME,
    // règles strictes (voir TransactionServiceImpl.normaliserRattachement). Absent = ancien
    // format (commun / projets concernés / site / poulailler), normalisé par le serveur.
    private String rattachement;
    private Boolean commun; // true = dépense/rentrée commune, false = liée à un seul projet
    private String projetUniqueId; // requis si commun = false
    private List<String> projetsConcernesUniqueIds; // optionnel, pertinent seulement si commun = true
    private String clientUniqueId; // optionnel — voir Transaction.client
    private LocalDate date;
    private String description;
    private Double montant;
    private String categorie;
    // Catégorie « Autre » : précision libre (ex. « Gardiennage »). Si categorie vaut
    // « Autre » et que la précision est remplie, c'est la précision qui est enregistrée
    // comme catégorie (même résultat que le champ « Préciser la catégorie » du web).
    // Absente (APK <= 1.35) : la catégorie reste « Autre », la description dit le reste.
    private String categoriePrecision;
    private Double quantite; // facultatif (Santé / Vétérinaire : obligatoire, > 0)
    private Double prixUnitaire; // facultatif ; absent = montant / quantite

    // Rattachements facultatifs (voir Transaction.site / Transaction.batiment) : absents = ferme entière.
    private String siteUniqueId;
    private String batimentUniqueId;
}
