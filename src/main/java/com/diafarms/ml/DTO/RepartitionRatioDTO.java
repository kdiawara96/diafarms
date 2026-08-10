package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Getter;

// Projection minimale (uniqueId de la ligne de répartition + montant théorique/rapporté
// de LA VENTE ENTIÈRE dont elle fait partie) — sert uniquement à calculer un ratio
// réel/théorique par ligne de répartition, voir TransactionServiceImpl.enrichMontantReel.
// Distinct de VenteRepartitionReelDTO (qui porte en plus projet+montantAttribue pour un
// agrégat par projet) : ici on veut juste le ratio, appliqué ensuite au montant déjà
// attribué au projet côté Transaction.montant.
@Getter
@AllArgsConstructor
public class RepartitionRatioDTO {
    private String repartitionUniqueId;
    private Double venteMontant;
    private Double venteMontantRapporte; // null = pas d'écart déclaré pour cette vente
}
