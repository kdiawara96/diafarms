package com.diafarms.ml.DTO;

import com.diafarms.ml.models.FactureLigne;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class FactureLigneDTO {
    private String uniqueId;
    private String venteType;
    private String venteUniqueId;
    private String description;
    private Integer quantite;
    private Double prixUnitaire;
    private Double montant;

    public static FactureLigneDTO fromEntity(FactureLigne l) {
        if (l == null) return null;
        return FactureLigneDTO.builder()
                .uniqueId(l.getUniqueId())
                .venteType(l.getVenteType() != null ? l.getVenteType().name() : null)
                .venteUniqueId(l.getVenteUniqueId())
                .description(l.getDescription())
                .quantite(l.getQuantite())
                .prixUnitaire(l.getPrixUnitaire())
                .montant(l.getMontant())
                .build();
    }
}
