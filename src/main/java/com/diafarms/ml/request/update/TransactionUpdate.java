package com.diafarms.ml.request.update;

import java.time.LocalDate;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionUpdate {
    private String type;
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
}
