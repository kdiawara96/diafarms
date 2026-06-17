package com.diafarms.ml.DTO;

import lombok.Data;
import java.util.Objects;

import com.diafarms.ml.models.ProjectAlertConfig;

@Data
public class ProjectAlertTableDTO {
    private Long id;
    private String niveau; // "CRITIQUE", "WARNING", "INFO"
    private String type;   // "Mortalité", "Alimentation", "Vaccination", "Climat"
    private String titre;  // Généré dynamiquement
    private boolean enabled;

    public static ProjectAlertTableDTO fromEntity(ProjectAlertConfig entity) {
        ProjectAlertTableDTO dto = new ProjectAlertTableDTO();
        dto.setId(entity.getId());
        dto.setNiveau(entity.getLevel().name());
        dto.setType(entity.getAlertType().getLabel()); // Utilise le label en français ("Mortalité", etc.)
        dto.setEnabled(entity.getStatus() == com.diafarms.ml.enums.AlertStatus.ACTIF);
        
        // Génération du titre dynamique côté Back-end
        dto.setTitre(generateBackEndTitle(entity));
        
        return dto;
    }

    private static String generateBackEndTitle(ProjectAlertConfig entity) {
        String key = entity.getThresholdKey().name();
        String unit = entity.getUnit() != null ? entity.getUnit() : "";
        String value = entity.getNumericValue() != null ? entity.getNumericValue().toString() : "";

        switch (entity.getAlertType()) {
            case MORTALITE:
                if (key.contains("WARNING")) {
                    return "Seuil Warning: " + value + unit;
                } else if (key.contains("CRITICAL") || key.contains("CUMULATIVE")) {
                    return "Seuil Critique: " + value + unit;
                }
                break;
            case ALIMENTATION:
                if (key.contains("STOCK")) {
                    return "Stock: " + value + " " + unit + " restants";
                }
                break;
            case METEO:
                if (key.contains("WARNING")) {
                    return "Temp. Warning: " + value + unit;
                } else if (key.contains("CRITICAL")) {
                    return "Temp. Critique: " + value + unit;
                } else if (key.contains("MAX") || key.contains("MIN")) {
                    return entity.getThresholdKey() + ": " + value + unit; // Humidité Max/Min
                }
                break;
            case VACCINATION:
                if (entity.getStringValue() != null) {
                    return entity.getStringValue(); // Nom du vaccin précis
                }
                return "Vaccination";
        }
        return "Alerte";
    }
}