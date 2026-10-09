package com.diafarms.ml.commons;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VenteHorsVentesTest {

    @Test
    void entreeQuiDecritUneVenteEstRefusee() {
        assertTrue(VenteHorsVentes.estVente("ENTREE", "Autre", "Vente œufs marché"));
        assertTrue(VenteHorsVentes.estVente("ENTREE", "Autre", "10 ALVÉOLES pour Awa"));
        assertTrue(VenteHorsVentes.estVente("ENTREE", "Autre", "réformées vendues"));
        assertTrue(VenteHorsVentes.estVente("ENTREE", "Fientes", "sacs"));
        assertTrue(VenteHorsVentes.estVente("entree", "Don", "argent des poulets"));
        assertTrue(VenteHorsVentes.estVente("ENTREE", "Vente œufs", null));
    }

    @Test
    void autresCasAcceptes() {
        assertFalse(VenteHorsVentes.estVente("SORTIE", "Alvéole", "Achat alvéoles"));
        assertFalse(VenteHorsVentes.estVente("ENTREE", "Location", "Location du poulailler"));
        assertFalse(VenteHorsVentes.estVente("ENTREE", "Don", "Aide de la famille"));
        assertFalse(VenteHorsVentes.estVente("ENTREE", "Autre", "Avance sur salaire remboursée"));
        assertFalse(VenteHorsVentes.estVente(null, "Autre", "vente"));
    }
}
