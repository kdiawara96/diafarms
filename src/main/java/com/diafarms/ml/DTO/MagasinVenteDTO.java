package com.diafarms.ml.DTO;

import java.util.List;
import java.util.stream.Collectors;

import com.diafarms.ml.models.MagasinVente;
import com.diafarms.ml.models.Utilisateurs;

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
public class MagasinVenteDTO {
    private Long id;
    private String uniqueId;
    private String nom;
    private String description;
    private Integer seuilAlerteOeufs;
    private Integer seuilAlerteReforme;
    private List<VendeurRefDTO> vendeurs;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class VendeurRefDTO {
        private String uniqueId;
        private String fullName;
    }

    public static MagasinVenteDTO fromEntity(MagasinVente m) {
        if (m == null) return null;
        return MagasinVenteDTO.builder()
                .id(m.getId())
                .uniqueId(m.getUniqueId())
                .nom(m.getNom())
                .description(m.getDescription())
                .seuilAlerteOeufs(m.getSeuilAlerteOeufs())
                .seuilAlerteReforme(m.getSeuilAlerteReforme())
                .vendeurs(m.getVendeurs() == null ? List.of() : m.getVendeurs().stream()
                        .map(MagasinVenteDTO::toVendeurRef)
                        .collect(Collectors.toList()))
                .build();
    }

    private static VendeurRefDTO toVendeurRef(Utilisateurs u) {
        return VendeurRefDTO.builder().uniqueId(u.getUniqueId()).fullName(u.getFullName()).build();
    }
}
