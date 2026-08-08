package com.diafarms.ml.DTO;

import com.diafarms.ml.models.FarmAppSettings;

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
public class FarmAppSettingsDTO {
    private boolean productionMobileEnabled;
    private boolean productionWebEnabled;
    private boolean comptableMobileEnabled;
    private boolean comptableWebEnabled;
    private boolean venteMobileEnabled;
    private boolean venteWebEnabled;
    private boolean responsableWebEnabled;

    public static FarmAppSettingsDTO fromEntity(FarmAppSettings s) {
        if (s == null) {
            return FarmAppSettingsDTO.builder().build(); // tout à false = comportement par défaut, sans accès
        }
        return FarmAppSettingsDTO.builder()
                .productionMobileEnabled(Boolean.TRUE.equals(s.getProductionMobileEnabled()))
                .productionWebEnabled(Boolean.TRUE.equals(s.getProductionWebEnabled()))
                .comptableMobileEnabled(Boolean.TRUE.equals(s.getComptableMobileEnabled()))
                .comptableWebEnabled(Boolean.TRUE.equals(s.getComptableWebEnabled()))
                .venteMobileEnabled(Boolean.TRUE.equals(s.getVenteMobileEnabled()))
                .venteWebEnabled(Boolean.TRUE.equals(s.getVenteWebEnabled()))
                .responsableWebEnabled(Boolean.TRUE.equals(s.getResponsableWebEnabled()))
                .build();
    }
}
