package com.diafarms.ml.request.update;

import java.util.List;

import lombok.Data;

@Data
public class VaccinUpdate {
    private String nomVaccin;
    private Integer quantite;
    private Double prixUnitaire;
    private List<String> modeAdministration; // Reçoit le tableau ["Oral", "Injection"]
}
