package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.Site;

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
public class SiteDTO {
    private String uniqueId;
    private String nom;
    private String localisation;
    private Double latitude;
    private Double longitude;
    private LocalDateTime createdAt;

    public static SiteDTO fromEntity(Site s) {
        if (s == null) return null;
        return SiteDTO.builder()
                .uniqueId(s.getUniqueId())
                .nom(s.getNom())
                .localisation(s.getLocalisation())
                .latitude(s.getLatitude())
                .longitude(s.getLongitude())
                .createdAt(s.getInitialisation() != null ? s.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
