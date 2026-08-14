package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class PersonnelCreate {
    private String nom;
    private String poste; // optionnel
    private String telephone; // optionnel
    private String utilisateurCompteUniqueId; // optionnel — lien vers un compte existant
}
