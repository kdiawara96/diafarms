package com.diafarms.ml.ServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EmailLiensTest {

    @Test
    void lienCliquableSansPonctuationFinale() {
        assertEquals("Voir <a href=\"https://cocoricoferme.com\" style=\"color: #15803d; font-weight: bold;\">https://cocoricoferme.com</a>.",
                EmailServiceImpl.liens("Voir https://cocoricoferme.com."));
    }

    @Test
    void lienAvecParametres() {
        String r = EmailServiceImpl.liens("https://www.youtube.com/watch?v=xkBuuq9Qqyw");
        assertEquals("<a href=\"https://www.youtube.com/watch?v=xkBuuq9Qqyw\" style=\"color: #15803d; font-weight: bold;\">https://www.youtube.com/watch?v=xkBuuq9Qqyw</a>", r);
    }

    @Test
    void texteSansLien() {
        assertEquals("Bonjour", EmailServiceImpl.liens("Bonjour"));
    }
}
