package com.diafarms.ml.DTO;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.diafarms.ml.models.ProjectAlertConfig;

@Data
public class ProjectAlertTableDTO {
    private Long id;
    private String niveau; // "CRITIQUE", "WARNING", "INFO"
    private String type;   // "Mortalité", "Alimentation", "Vaccination", "Climat"
    private String titre;  // Généré dynamiquement
    private boolean enabled;

    //  AJOUTE CES TROIS CHAMPS ICI :
    private String thresholdKey; // ex: "DAILY_WARNING"
    private BigDecimal numericValue; // Le seuil chiffré (ex: 0.5)
    private String stringValue;  // Le texte (ex: "Gumboro")
    private LocalDate date; // L'unité (ex: "%")

    public static ProjectAlertTableDTO fromEntity(ProjectAlertConfig entity) {
        ProjectAlertTableDTO dto = new ProjectAlertTableDTO();
        dto.setId(entity.getId());
        dto.setNiveau(entity.getLevel().name());
        dto.setType(entity.getAlertType().getLabel()); // Utilise le label en français ("Mortalité", etc.)
        dto.setEnabled(entity.getStatus() == com.diafarms.ml.enums.AlertStatus.ACTIF);
        
        // Génération du titre dynamique côté Back-end
        dto.setTitre(generateBackEndTitle(entity));

        //  REREMPLIT LES NOUVEAUX CHAMPS DEPUIS L'ENTITÉ :
        if (entity.getThresholdKey() != null) {
            dto.setThresholdKey(entity.getThresholdKey().name()); // Récupère le nom de l'enum (ex: "DAILY_WARNING")
        }
        
        dto.setNumericValue(entity.getNumericValue());
        dto.setStringValue(entity.getStringValue());
        dto.setDate(entity.getDateValue());
        
        return dto;
    }

    private static String generateBackEndTitle(ProjectAlertConfig entity) {
        // Sécurité au cas où la clé de seuil est nulle
        if (entity.getThresholdKey() == null) {
            return "Alerte " + (entity.getAlertType() != null ? entity.getAlertType().getLabel() : "");
        }

        String unit = entity.getUnit() != null ? entity.getUnit() : "";
        String value = entity.getNumericValue() != null ? entity.getNumericValue().toString() : "";
        com.diafarms.ml.enums.ThresholdKey key = entity.getThresholdKey();

        switch (entity.getAlertType()) {
            case MORTALITE:
                // Utilisation des valeurs précises de l'enum pour distinguer les 3 seuils critiques
                switch (key) {
                    case DAILY_WARNING:
                        return "Seuil Warning Journalier: " + value + unit;
                    case DAILY_CRITICAL:
                        return "Seuil Critique Journalier: " + value + unit;
                    case WEEKLY_CRITICAL:
                        return "Seuil Critique Hebdomadaire: " + value + unit;
                    case CUMULATIVE_CRITICAL:
                        return "Seuil Critique Cumulé: " + value + unit;
                    default:
                        return "Alerte Mortalité: " + value + unit;
                }

            case ALIMENTATION:
                // On peut utiliser le label de l'enum ou garder ton formatage explicite
                return "Stock: " + value + " " + unit + " restants";

            case METEO:
                // Permet de gérer proprement les températures et l'humidité selon la clé
                if (key == com.diafarms.ml.enums.ThresholdKey.DAILY_WARNING) {
                    return "Temp. Warning: " + value + unit;
                } else if (key == com.diafarms.ml.enums.ThresholdKey.DAILY_CRITICAL) {
                    return "Temp. Critique: " + value + unit;
                } else {
                    // Pour les autres clés météo (comme des seuils MAX/MIN d'humidité par exemple)
                    return key.getLabel() + ": " + value + unit;
                }

            case VACCINATION:
                if (entity.getStringValue() != null && !entity.getStringValue().isEmpty()) {
                    return "Vaccin: " + entity.getStringValue(); // Ex: Vaccin: Gumboro
                }
                return key.getLabel(); // Retournera le label de l'enum par défaut
                
            default:
                return "Alerte";
        }
    }

}