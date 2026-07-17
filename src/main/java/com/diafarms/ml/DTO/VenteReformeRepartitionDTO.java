package com.diafarms.ml.DTO;

import com.diafarms.ml.models.VenteReformeRepartition;

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
public class VenteReformeRepartitionDTO {
    private String projetCode;
    private String projetUniqueId;
    private Integer nombreSujetsAttribue;
    private Double montantAttribue;

    public static VenteReformeRepartitionDTO fromEntity(VenteReformeRepartition r) {
        if (r == null) return null;
        return VenteReformeRepartitionDTO.builder()
                .projetCode(r.getProjet() != null ? r.getProjet().getCode() : null)
                .projetUniqueId(r.getProjet() != null ? r.getProjet().getUniqueId() : null)
                .nombreSujetsAttribue(r.getNombreSujetsAttribue())
                .montantAttribue(r.getMontantAttribue())
                .build();
    }
}
