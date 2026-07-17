package com.diafarms.ml.DTO;

import com.diafarms.ml.models.VenteOeufsRepartition;

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
public class VenteOeufsRepartitionDTO {
    private String projetCode;
    private String projetUniqueId;
    private Integer quantiteAttribuee;
    private Double montantAttribue;

    public static VenteOeufsRepartitionDTO fromEntity(VenteOeufsRepartition r) {
        if (r == null) return null;
        return VenteOeufsRepartitionDTO.builder()
                .projetCode(r.getProjet() != null ? r.getProjet().getCode() : null)
                .projetUniqueId(r.getProjet() != null ? r.getProjet().getUniqueId() : null)
                .quantiteAttribuee(r.getQuantiteAttribuee())
                .montantAttribue(r.getMontantAttribue())
                .build();
    }
}
