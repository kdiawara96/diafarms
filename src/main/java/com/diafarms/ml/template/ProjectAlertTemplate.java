package com.diafarms.ml.template;

import java.math.BigDecimal;

import com.diafarms.ml.enums.AlertLevel;
import com.diafarms.ml.enums.AlertType;
import com.diafarms.ml.enums.ThresholdKey;

public enum ProjectAlertTemplate {

    // --- MORTALITÉ ---
    MORT_DAILY_WARNING(AlertType.MORTALITE, ThresholdKey.DAILY_WARNING, AlertLevel.WARNING, new BigDecimal("0.1"), "%"),
    MORT_DAILY_CRITICAL(AlertType.MORTALITE, ThresholdKey.DAILY_CRITICAL, AlertLevel.CRITIQUE, new BigDecimal("0.5"), "%"),
    MORT_WEEKLY_CRITICAL(AlertType.MORTALITE, ThresholdKey.WEEKLY_CRITICAL, AlertLevel.CRITIQUE, new BigDecimal("2.0"), "%"),
    MORT_CUMULATIVE_CRITICAL(AlertType.MORTALITE, ThresholdKey.CUMULATIVE_CRITICAL, AlertLevel.CRITIQUE, new BigDecimal("5.0"), "%"),

    // --- ALIMENTATION ---
    FOOD_STOCK_DAYS(AlertType.ALIMENTATION, ThresholdKey.DAILY_WARNING, AlertLevel.WARNING, new BigDecimal("2.0"), "jours"),

    // --- MÉTÉO ---
    WEATHER_TEMP_WARNING(AlertType.METEO, ThresholdKey.DAILY_WARNING, AlertLevel.WARNING, new BigDecimal("30.0"), "°C"),
    WEATHER_TEMP_CRITICAL(AlertType.METEO, ThresholdKey.DAILY_CRITICAL, AlertLevel.CRITIQUE, new BigDecimal("30.0"), "°C"),
    WEATHER_HUMIDITY_MAX(AlertType.METEO, ThresholdKey.CUMULATIVE_CRITICAL, AlertLevel.WARNING, new BigDecimal("75.0"), "%"), // À ajuster selon tes clés exactes
    WEATHER_HUMIDITY_MIN(AlertType.METEO, ThresholdKey.WEEKLY_CRITICAL, AlertLevel.WARNING, new BigDecimal("45.0"), "%");

    private final AlertType alertType;
    private final ThresholdKey thresholdKey;
    private final AlertLevel level;
    private final BigDecimal numericValue;
    private final String unit;

    ProjectAlertTemplate(AlertType alertType, ThresholdKey thresholdKey, AlertLevel level, BigDecimal numericValue, String unit) {
        this.alertType = alertType;
        this.thresholdKey = thresholdKey;
        this.level = level;
        this.numericValue = numericValue;
        this.unit = unit;
    }

    // Getters
    public AlertType getAlertType() { return alertType; }
    public ThresholdKey getThresholdKey() { return thresholdKey; }
    public AlertLevel getLevel() { return level; }
    public BigDecimal getNumericValue() { return numericValue; }
    public String getUnit() { return unit; }
}