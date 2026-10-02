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
    // Magasin de stockage des réformés (comme une collecte). Optionnel : les anciens
    // téléphones ne l'envoient pas, voir ReformeStockage.resoudre pour le défaut.
    private String magasinStockageUniqueId;
    // ANCIEN contrat (point de vente direct) : sert seulement à retrouver le magasin de
    // stockage dont c'est le point de vente par défaut.
    private String magasinVenteUniqueId;
}
