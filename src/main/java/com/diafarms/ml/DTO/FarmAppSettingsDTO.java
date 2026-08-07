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
    private boolean producteurMobileEnabled;
    private boolean producteurWebEnabled;
    private boolean financierWebEnabled;
    private boolean financierMobileVenteOeufs;
    private boolean financierMobileVenteReforme;
    private boolean financierMobileVenteFientes;
    private boolean financierMobileEntree;
    private boolean financierMobileSortie;

    public static FarmAppSettingsDTO fromEntity(FarmAppSettings s) {
        if (s == null) {
            return FarmAppSettingsDTO.builder().build(); // tout à false = comportement par défaut, sans accès
        }
        return FarmAppSettingsDTO.builder()
                .producteurMobileEnabled(Boolean.TRUE.equals(s.getProducteurMobileEnabled()))
                .producteurWebEnabled(Boolean.TRUE.equals(s.getProducteurWebEnabled()))
                .financierWebEnabled(Boolean.TRUE.equals(s.getFinancierWebEnabled()))
                .financierMobileVenteOeufs(Boolean.TRUE.equals(s.getFinancierMobileVenteOeufs()))
                .financierMobileVenteReforme(Boolean.TRUE.equals(s.getFinancierMobileVenteReforme()))
                .financierMobileVenteFientes(Boolean.TRUE.equals(s.getFinancierMobileVenteFientes()))
                .financierMobileEntree(Boolean.TRUE.equals(s.getFinancierMobileEntree()))
                .financierMobileSortie(Boolean.TRUE.equals(s.getFinancierMobileSortie()))
                .build();
    }
}
