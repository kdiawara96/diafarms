package com.diafarms.ml.request.update;

import java.time.LocalDate;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionUpdate {
    private String type;
    // PROJET | SITE | FERME (voir TransactionCreate.rattachement) : fourni, il remplace tout
    // le rattachement (projet, site, poulailler) ; absent, ancien format ci-dessous.
    private String rattachement;
    // null = on ne touche pas au rattachement projet ; true/false = changement explicite
    // (nécessaire car un simple `projetUniqueId: null` dans le JSON est ambigu entre
    // "champ non fourni" et "je veux passer en Commun").
    private Boolean commun;
    private String projetUniqueId; // requis si commun = false
    private List<String> projetsConcernesUniqueIds; // pertinent seulement si commun = true
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

    // Rattachements facultatifs, même principe que `commun` (un `null` JSON est ambigu) :
    // absent (null) = inchangé ; chaîne VIDE = retirer le rattachement ; valeur = le définir.
    private String siteUniqueId;
    private String batimentUniqueId;
}
