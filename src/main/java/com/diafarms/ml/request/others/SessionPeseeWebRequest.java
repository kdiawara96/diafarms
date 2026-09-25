package com.diafarms.ml.request.others;

import lombok.Data;

// Actions web sur une session de pesée (un seul corps pour toutes les routes, chaque
// route ne lit que ses champs) :
//  - création     : projetUniqueId, nombreParDefaut
//  - ajout/modif  : nombreSujets, poidsKg
//  - terminaison  : dateFin (facultative, date-heure ISO)
@Data
public class SessionPeseeWebRequest {
    private String projetUniqueId;
    private Integer nombreParDefaut;
    private Integer nombreSujets;
    private Double poidsKg;
    private String dateFin;
}
