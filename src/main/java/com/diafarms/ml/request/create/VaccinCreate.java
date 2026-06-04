package com.diafarms.ml.request.create;

import lombok.Data;
import java.util.List;

@Data
public class VaccinCreate {
    private String nomVaccin;
    private Integer quantite;
    private Double prixUnitaire;
    private Double coutTotal;
    // Ton front envoie un tableau de chaînes (ex: ["Oral", "Injection"])
    // On peut le stocker sous forme de liste ou le joindre en String plus tard
    private List<String> modeAdministration; 
}