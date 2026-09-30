package com.diafarms.ml.commons;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;

// Date saisie par l'utilisateur (paiement, facture, commande...) : LocalDate.parse lève
// DateTimeParseException, qui n'est PAS une IllegalArgumentException et remontait donc
// en 500 dans les contrôleurs. Ici une date mal formée devient une
// IllegalArgumentException (« Date invalide ») -> 400, comme les autres erreurs de saisie.
public final class DateSaisie {

    private DateSaisie() {}

    public static final String MESSAGE_FUTUR = "La date ne peut pas être dans le futur";

    /** Tolérance : un téléphone dont l'horloge avance (ou un autre fuseau) peut saisir
     * « demain » ; au-delà, c'est une erreur de saisie. */
    public static final int JOURS_TOLERANCE_FUTUR = 1;

    /** Date d'une saisie (collecte, soin, vente, paiement, achat...) : refusée si elle est
     * après aujourd'hui + 1 jour (IllegalArgumentException -> 400). null passe tel quel. */
    public static LocalDate pasDansLeFutur(LocalDate date) {
        if (date != null && date.isAfter(LocalDate.now().plusDays(JOURS_TOLERANCE_FUTUR))) {
            throw new IllegalArgumentException(MESSAGE_FUTUR + " (" + date + ").");
        }
        return date;
    }

    /** Idem pour une date et heure (pesées). */
    public static java.time.LocalDateTime pasDansLeFutur(java.time.LocalDateTime dateHeure) {
        if (dateHeure != null) pasDansLeFutur(dateHeure.toLocalDate());
        return dateHeure;
    }

    /** parse puis pasDansLeFutur : la date d'une saisie envoyée par le web ou le mobile. */
    public static LocalDate saisie(String brute, LocalDate defaut) {
        return pasDansLeFutur(parse(brute, defaut));
    }

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
