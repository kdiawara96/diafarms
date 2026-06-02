package com.diafarms.ml.enums;

import java.math.BigDecimal;

public enum DefaultThreshold {

    // MORTALITE
    MORT_DAILY_WARNING(AlertType.MORTALITE, "DAILY_WARNING", AlertLevel.WARNING,
                       new BigDecimal("0.1"), "%", "Seuil Journalier Warning"),

    MORT_DAILY_CRITICAL(AlertType.MORTALITE, "DAILY_CRITICAL", AlertLevel.CRITIQUE,
                        new BigDecimal("0.5"), "%", "Seuil Journalier Critique"),

    MORT_WEEKLY_CRITICAL(AlertType.MORTALITE, "WEEKLY_CRITICAL", AlertLevel.CRITIQUE,
                         new BigDecimal("2"), "%", "Seuil Hebdomadaire Critique"),

    MORT_CUMULATIVE_CRITICAL(AlertType.MORTALITE, "CUMULATIVE_CRITICAL", AlertLevel.CRITIQUE,
                             new BigDecimal("5"), "%", "Seuil Cumulé Critique"),

    // ALIMENTATION
    FOOD_STOCK_DAYS(AlertType.ALIMENTATION, "STOCK_DAYS", AlertLevel.WARNING,
                    new BigDecimal("2"), "jours", "Stock restants"),

    // METEO
    WEATHER_TEMP_WARNING(AlertType.METEO, "TEMP_WARNING", AlertLevel.WARNING,
                         new BigDecimal("30"), "°C", "Température Warning"),

    WEATHER_TEMP_CRITICAL(AlertType.METEO, "TEMP_CRITICAL", AlertLevel.CRITIQUE,
                          new BigDecimal("30"), "°C", "Température Critique"),

    WEATHER_HUMIDITY_MAX(AlertType.METEO, "HUMIDITY_MAX", AlertLevel.WARNING,
                         new BigDecimal("75"), "%", "Humidité Max"),

    WEATHER_HUMIDITY_MIN(AlertType.METEO, "HUMIDITY_MIN", AlertLevel.WARNING,
                         new BigDecimal("45"), "%", "Humidité Min");

    private final AlertType type;
    private final String key;
    private final AlertLevel level;
    private final BigDecimal defaultValue;
    private final String unit;
    private final String label;

    DefaultThreshold(AlertType type, String key, AlertLevel level,
                     BigDecimal defaultValue, String unit, String label) {
        this.type = type;
        this.key = key;
        this.level = level;
        this.defaultValue = defaultValue;
        this.unit = unit;
        this.label = label;
    }

    // Getters...
    public AlertType getType() { return type; }
    public String getKey() { return key; }
    public AlertLevel getLevel() { return level; }
    public BigDecimal getDefaultValue() { return defaultValue; }
    public String getUnit() { return unit; }
    public String getLabel() { return label; }
}