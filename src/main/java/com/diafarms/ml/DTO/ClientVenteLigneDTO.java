package com.diafarms.ml.DTO;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne de l'historique d'achats d'un client (voir ClientReportDTO) — une vente
// d'œufs ou de réforme, peu importe, unifiées ici pour l'affichage chronologique.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ClientVenteLigneDTO {
    private String uniqueId;
    private LocalDate date;
    private String type; // "OEUFS" ou "REFORME"
    private String magasinNom;
    private Double montant; // théorique
    private Double montantRapporte; // null = pas d'écart déclaré pour cette vente
}
