package com.diafarms.ml.DTO;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

// Résultat agrégé par projet : montant théorique de ses ventes (œufs + réforme) sur
// la période, et sa part du montant réellement rapporté — voir
// TransactionServiceImpl.getVentesReelParProjet. Sert au web (Reporting.tsx) à
// corriger "Entrées" par projet, qui ne comptait jusqu'ici que le théorique.
@Getter
@Setter
@Builder
@AllArgsConstructor
public class ProjetVenteReelDTO {
    private String projetUniqueId;
    private String projetCode;
    private Double montantTheorique;
    private Double montantReel;
}
