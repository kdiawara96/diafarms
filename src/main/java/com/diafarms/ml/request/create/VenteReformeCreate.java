package com.diafarms.ml.request.create;

import java.util.List;

import lombok.Data;

// Pas de projetUniqueId : vente Finance à l'échelle de la ferme entière, voir
// VenteReformeImpl. projetsConcernesUniqueIds (optionnel) tague les projets qui ont
// contribué au lot vendu — voir VenteOeufsCreate pour le détail du mécanisme.
@Data
public class VenteReformeCreate {
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer nombreSujets;
    private Double prixUnitaire; // optionnel, informatif
    private Double montant;
    private List<String> projetsConcernesUniqueIds; // optionnel
}
