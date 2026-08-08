package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Stock vendable disponible DANS un magasin précis (transféré - déjà vendu, voir
// VenteOeufsImpl/VenteReformeImpl.disponibleParProjetDansMagasin pour le détail
// par projet contributeur) — distinct du stock farm-wide non encore transféré
// (voir MagasinTransfertService.disponibleATransfererDepuisProjet).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class StockMagasinDTO {
    private int oeufsDisponible;
    private int reformeDisponible;
}
