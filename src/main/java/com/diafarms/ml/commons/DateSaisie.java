package com.diafarms.ml.commons;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

// Date saisie par l'utilisateur (paiement, facture, commande) : LocalDate.parse lève
// DateTimeParseException, qui n'est PAS une IllegalArgumentException et remontait donc
// en 500 dans les contrôleurs. Ici une date mal formée devient une
// IllegalArgumentException (« Date invalide ») -> 400, comme les autres erreurs de saisie.
public final class DateSaisie {

    private DateSaisie() {}

    /** Vide ou null -> defaut ; sinon AAAA-MM-JJ, ou IllegalArgumentException. */
    public static LocalDate parse(String brute, LocalDate defaut) {
        if (brute == null || brute.isBlank()) return defaut;
        try {
            return LocalDate.parse(brute.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Date invalide : " + brute + " (format attendu AAAA-MM-JJ).");
        }
    }
}
