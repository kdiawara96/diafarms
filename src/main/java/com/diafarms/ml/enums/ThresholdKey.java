package com.diafarms.ml.enums;

public enum ThresholdKey {
    DAILY_WARNING("Journalier Warning"),
    DAILY_CRITICAL("Journalier Critique"),
    WEEKLY_CRITICAL("Hebdomadaire Critique"),
    CUMULATIVE_CRITICAL("Cumulé Critique");

    private final String label;

    ThresholdKey(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
