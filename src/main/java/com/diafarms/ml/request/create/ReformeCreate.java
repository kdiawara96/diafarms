package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class ReformeCreate {
    private String projetUniqueId;
    private String batimentUniqueId; // optionnel
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer nombreSujets;
    private String cause; // optionnel
    // Point de vente où placer les réformés (optionnel : défaut = ReformePointDeVente.parDefaut ;
    // les anciens téléphones ne l'envoient pas).
    private String magasinVenteUniqueId;
}
