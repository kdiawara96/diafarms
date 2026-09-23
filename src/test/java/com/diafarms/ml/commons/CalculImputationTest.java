package com.diafarms.ml.commons;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.diafarms.ml.commons.CalculImputation.*;

class CalculImputationTest {

    private static double total(List<Affectation> a) {
        return a.stream().mapToDouble(Affectation::montant).sum();
    }

    @Test
    void acompteSurPremiereLivraisonPuisResteEnAvance() {
        // Scénario 3 : acompte 40 000, livraison 60 000.
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 40000, "C1", null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 60000, "C1")));
        assertEquals(1, a.size());
        assertEquals(40000, a.get(0).montant());
        assertEquals("V1", a.get(0).cibleUniqueId());
    }

    @Test
    void plusieursLivraisonsFifo() {
        // Scénario 9 : L1 déjà réglée de 40 000 par l'acompte ; nouveau paiement 60 000.
        var a = CalculImputation.repartir(
                List.of(new Source("P2", 60000, null, null)),
                List.of(new Besoin("VENTE_OEUFS", "L1", 20000, "C1"),
                        new Besoin("VENTE_OEUFS", "L2", 40000, "C1")));
        assertEquals(2, a.size());
        assertEquals("L1", a.get(0).cibleUniqueId());
        assertEquals(20000, a.get(0).montant());
        assertEquals("L2", a.get(1).cibleUniqueId());
        assertEquals(40000, a.get(1).montant());
    }

    @Test
    void venteCibleesPrioritaire() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 5000, null, "V2")),
                List.of(new Besoin("VENTE_OEUFS", "V1", 5000, null),
                        new Besoin("VENTE_OEUFS", "V2", 5000, null)));
        assertEquals("V2", a.get(0).cibleUniqueId());
        assertEquals(5000, total(a));
    }

    @Test
    void commandeDuPaiementPrioritaireSurLesAutresVentes() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 3000, "C2", null)),
                List.of(new Besoin("VENTE_OEUFS", "VieilleVente", 3000, null),
                        new Besoin("VENTE_REFORME", "LivraisonC2", 3000, "C2")));
        assertEquals("LivraisonC2", a.get(0).cibleUniqueId());
    }

    @Test
    void troppercuResteNonImpute() {
        // Scénario 8 : 40 000 payés, 30 000 livrés.
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 40000, "C1", null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 30000, "C1")));
        assertEquals(30000, total(a));
    }

    @Test
    void jamaisPlusQueLeResteDUneSourceNiDUnBesoin() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 100, null, null), new Source("P2", 100, null, null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 150, null)));
        assertEquals(150, total(a));
        assertEquals(100, a.get(0).montant());
        assertEquals(50, a.get(1).montant());
    }

    @Test
    void resteNulOuNegatifIgnore() {
        var a = CalculImputation.repartir(
                List.of(new Source("P1", 0, null, null), new Source("P2", -5, null, null)),
                List.of(new Besoin("VENTE_OEUFS", "V1", 100, null)));
        assertTrue(a.isEmpty());
    }

    @Test
    void remboursementPrendDAbordLaCommandePuisLesPlusRecents() {
        var a = CalculImputation.prelever(
                List.of(new Source("Ancien", 10000, null, null),
                        new Source("AcompteC1", 5000, "C1", null),
                        new Source("Recent", 10000, null, null)),
                "REMBOURSEMENT", "R1", 12000, "C1");
        assertEquals("AcompteC1", a.get(0).paiementUniqueId());
        assertEquals(5000, a.get(0).montant());
        assertEquals("Recent", a.get(1).paiementUniqueId());
        assertEquals(7000, a.get(1).montant());
    }

    @Test
    void remboursementSuperieurALAvanceRefuse() {
        assertThrows(IllegalArgumentException.class, () -> CalculImputation.prelever(
                List.of(new Source("P1", 1000, null, null)), "REMBOURSEMENT", "R1", 1500, null));
    }

    @Test
    void arrondiAuCentime() {
        assertEquals(0.3, CalculImputation.arrondi(0.1 + 0.2));
    }
}
