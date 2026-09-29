package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class AffectationPersonnelCreate {
    private String projetUniqueId;
    private String dateDebut; // AAAA-MM-JJ, obligatoire
    private String dateFin; // AAAA-MM-JJ, facultatif (vide = en cours) ; en modification, "" = rouvrir
}
