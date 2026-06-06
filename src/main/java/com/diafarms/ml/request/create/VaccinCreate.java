package com.diafarms.ml.request.create;

import lombok.Data;
import java.util.List;

@Data
public class VaccinCreate {
    private String nomVaccin;
    private Integer quantite;
    private Double prixUnitaire;
    private Double coutTotal;
    private List<String> modeAdministration; // Reçoit le tableau ["Oral", "Injection"]
}