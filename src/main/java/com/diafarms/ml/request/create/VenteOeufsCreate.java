package com.diafarms.ml.request.create;

import java.util.List;

import lombok.Data;

// Pas de projetUniqueId : vente Finance à l'échelle de la ferme entière, voir
// VenteOeufsImpl. projetsConcernesUniqueIds (optionnel) tague les projets qui ont
// contribué au lot vendu — même mécanique que TransactionCreate pour les
// transactions "communes" (association informative, pas de répartition
// proportionnelle du montant entre les projets tagués).
@Data
public class VenteOeufsCreate {
    private String date;
    private String heure; // "HH:mm", optionnel
    private Integer quantiteOeufs;
    private Double prixUnitaire; // optionnel, informatif
    private Double montant;
    private List<String> projetsConcernesUniqueIds; // optionnel
}
