package com.diafarms.ml.security;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class LimiteurTentativesTest {

    @Test
    void bloqueAuMaximumPuisDebloqueApresEffacement() {
        LimiteurTentatives l = new LimiteurTentatives();
        Duration f = Duration.ofMinutes(15);
        for (int i = 1; i <= 4; i++) {
            assertFalse(l.echec("login:a|ip", 5, f));
            assertFalse(l.estBloque("login:a|ip", 5));
        }
        assertTrue(l.echec("login:a|ip", 5, f));
        assertTrue(l.estBloque("login:a|ip", 5));
        assertTrue(l.minutesRestantes("login:a|ip", 5) >= 1);
        assertFalse(l.estBloque("login:b|ip", 5), "une autre clé n'est pas touchée");
        l.effacer("login:a|ip");
        assertFalse(l.estBloque("login:a|ip", 5));
    }

    @Test
    void fenetreExpireeRemetAZero() {
        LimiteurTentatives l = new LimiteurTentatives();
        for (int i = 0; i < 3; i++) l.echec("k", 3, Duration.ZERO);
        assertFalse(l.estBloque("k", 3), "fenêtre de 0 : déjà expirée");
    }
}
