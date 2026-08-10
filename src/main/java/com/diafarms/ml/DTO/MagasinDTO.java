package com.diafarms.ml.DTO;

import java.util.List;
import java.util.stream.Collectors;

import com.diafarms.ml.models.Magasin;
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
public class MagasinDTO {
    private Long id;
    private String uniqueId;
    private String nom;
    private String type;
    private String description;
    private Integer seuilAlerteOeufs;
    private Integer seuilAlerteReforme;
    private Integer seuilAlerteAlveoles;
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

    public static MagasinDTO fromEntity(Magasin m) {
        if (m == null) return null;
        return MagasinDTO.builder()
                .id(m.getId())
                .uniqueId(m.getUniqueId())
                .nom(m.getNom())
                .type(m.getType() != null ? m.getType().name() : null)
                .description(m.getDescription())
                .seuilAlerteOeufs(m.getSeuilAlerteOeufs())
                .seuilAlerteReforme(m.getSeuilAlerteReforme())
                .seuilAlerteAlveoles(m.getSeuilAlerteAlveoles())
                .vendeurs(m.getVendeurs() == null ? List.of() : m.getVendeurs().stream()
                        .map(MagasinDTO::toVendeurRef)
                        .collect(Collectors.toList()))
                .build();
    }

    private static VendeurRefDTO toVendeurRef(Utilisateurs u) {
        return VendeurRefDTO.builder().uniqueId(u.getUniqueId()).fullName(u.getFullName()).build();
    }
}
