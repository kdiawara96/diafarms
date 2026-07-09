package com.diafarms.ml.request.create;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionCreate {
    private String type; // "ENTREE" | "SORTIE"
    private String projetUniqueId; // optionnel, null = Commun
    private LocalDate date;
    private String description;
    private Double montant;
    private String categorie;
}
