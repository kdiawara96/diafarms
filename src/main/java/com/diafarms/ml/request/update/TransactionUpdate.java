package com.diafarms.ml.request.update;

import java.time.LocalDate;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransactionUpdate {
    private String type;
    private String projetUniqueId;
    private LocalDate date;
    private String description;
    private Double montant;
    private String categorie;
}
