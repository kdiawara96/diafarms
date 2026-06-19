package com.diafarms.ml.DTO;

import com.diafarms.ml.enums.AlertType;
import com.diafarms.ml.enums.ThresholdKey;
import lombok.Data;

@Data
public class UpdateAlertRequestDTO {
    private Long id; // Peut être null pour une nouvelle vaccination
    private AlertType alertType; // ENUM: MORTALITE, ALIMENTATION, VACCINATION, METEO
    private ThresholdKey thresholdKey; // ENUM: DAILY_WARNING, etc.
    private Double numericValue;
    private String stringValue; // Contient le nom du vaccin
    private boolean enabled;
    private String date;

    // Tu peux ajouter une date si tu la passes séparément ou la concaténer dans stringValue
}