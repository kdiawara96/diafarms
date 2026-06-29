package com.diafarms.ml.enums;


import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import lombok.Getter;

@Getter
public enum TokenDuration {
    HOURS_1("1h", 1, ChronoUnit.HOURS),
    HOURS_24("24h", 24, ChronoUnit.HOURS),
    DAYS_7("7d", 7, ChronoUnit.DAYS),
    DAYS_30("30d", 30, ChronoUnit.DAYS),
    PERMANENT("permanent", null, null);

    private final String value;
    private final Integer amount;
    private final ChronoUnit unit;

    TokenDuration(String value, Integer amount, ChronoUnit unit) {
        this.value = value;
        this.amount = amount;
        this.unit = unit;
    }

    public static TokenDuration fromValue(String value) {
        return Arrays.stream(values())
                .filter(d -> d.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Durée invalide: " + value));
    }

    public boolean isPermanent() {
        return this == PERMANENT;
    }

    public Instant calculateExpiry(Instant start) {
        if (isPermanent()) return start.plus(36500, ChronoUnit.DAYS); // ~100 ans
        return start.plus(amount, unit);
    }
}