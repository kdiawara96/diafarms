package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Part d'un projet dans une vente (page Ventes, fiche client : quantité et montant
// attribués par la répartition) ou dans un paiement client (popup sous « Commun » de la
// Comptabilité, voir EncaissementProjetService.repartitionPaiements : montant seul).
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PartProjetDTO {
    private String projetUniqueId;
    private String code;
    private String titre;
    // Œufs ou sujets attribués au projet (vente) ; null pour un paiement.
    private Double quantite;
    private Double montant;
}
