package com.diafarms.ml.request.create;

import java.util.List;

import lombok.Data;

@Data
public class SoinsCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure; // "HH:mm", optionnel
    private String type; // "VACCINATION" | "MEDICAMENT" | "AUTRE"
    private String produit;
    private Double quantite;
    private Double prixUnitaire; // renseigné seulement si type = VACCINATION — voir Soins.calculerCoutTotal
    private Double coutTotal;
    private List<String> modeAdministration; // ex: ["Oral", "Injection"] — renseigné seulement si type = VACCINATION
    private String observations;
}
