package com.diafarms.ml.request.create;

import java.time.LocalDate;
import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionCreate {
    private String type; // "ENTREE" | "SORTIE"
    private Boolean commun; // true = dépense/rentrée commune, false = liée à un seul projet
    private String projetUniqueId; // requis si commun = false
    private List<String> projetsConcernesUniqueIds; // optionnel, pertinent seulement si commun = true
    private LocalDate date;
    private String description;
    private Double montant;
    private String categorie;
}
