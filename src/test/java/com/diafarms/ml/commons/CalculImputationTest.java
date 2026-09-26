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

    // --- Règle « acompte réservé » (2026-09-26) ------------------------------------

    @Test
    void acompteReserveNePaiePasUneAncienneVente() {
        // Le client doit 20 000 d'une ancienne vente ; acompte 10 000 sur une commande ouverte.
        var a = CalculImputation.repartir(
                List.of(new Source("Acompte", 10000, "C1", null, true)),
                List.of(new Besoin("VENTE_OEUFS", "Ancienne", 20000, null)));
        assertTrue(a.isEmpty());
    }

    @Test
    void acompteReserveNePaiePasLaVenteDUneAutreCommande() {
        var a = CalculImputation.repartir(
                List.of(new Source("Acompte", 10000, "C1", null, true)),
                List.of(new Besoin("VENTE_OEUFS", "LivraisonC2", 5000, "C2")));
        assertTrue(a.isEmpty());
    }

    @Test
    void livraisonRegleeDAbordParLAcompteReservePuisResteDu() {
        // Ancienne vente 20 000 ; acompte 10 000 ; livraison 17 480.
        var a = CalculImputation.repartir(
                List.of(new Source("Acompte", 10000, "C1", null, true)),
                List.of(new Besoin("VENTE_OEUFS", "Ancienne", 20000, null),
                        new Besoin("VENTE_OEUFS", "Livraison", 17480, "C1")));
        assertEquals(1, a.size());
        assertEquals("Livraison", a.get(0).cibleUniqueId());
        assertEquals(10000, a.get(0).montant());
    }

    @Test
    void acompteReservePasseAvantLArgentLibrePlusAncien() {
        // Argent libre plus ancien (P0) et acompte réservé (P1) : la livraison de C1 est
        // réglée d'abord par l'acompte, l'argent libre va à l'ancienne vente.
        var a = CalculImputation.repartir(
                List.of(new Source("P0", 5000, null, null, false),
                        new Source("P1", 8000, "C1", null, true)),
                List.of(new Besoin("VENTE_OEUFS", "Ancienne", 5000, null),
                        new Besoin("VENTE_OEUFS", "Livraison", 8000, "C1")));
        assertEquals(2, a.size());
        assertEquals("P1", a.get(0).paiementUniqueId());
        assertEquals("Livraison", a.get(0).cibleUniqueId());
        assertEquals(8000, a.get(0).montant());
        assertEquals("P0", a.get(1).paiementUniqueId());
        assertEquals("Ancienne", a.get(1).cibleUniqueId());
    }

    @Test
    void resteDeLAcompteGardePourLesLivraisonsSuivantes() {
        var a = CalculImputation.repartir(
                List.of(new Source("Acompte", 30000, "C1", null, true)),
                List.of(new Besoin("VENTE_OEUFS", "Ancienne", 20000, null),
                        new Besoin("VENTE_OEUFS", "L1", 12000, "C1")));
        assertEquals(1, a.size());
        assertEquals(12000, total(a)); // 18 000 restent réservés à C1
    }

    @Test
    void argentLibrePeutReglerUneLivraisonApresLAcompte() {
        var a = CalculImputation.repartir(
                List.of(new Source("Libre", 10000, null, null, false),
                        new Source("Acompte", 4000, "C1", null, true)),
                List.of(new Besoin("VENTE_OEUFS", "L1", 10000, "C1")));
        assertEquals("Acompte", a.get(0).paiementUniqueId());
        assertEquals(4000, a.get(0).montant());
        assertEquals("Libre", a.get(1).paiementUniqueId());
        assertEquals(6000, a.get(1).montant());
    }

    @Test
    void commandeTermineeAcompteLibreRegleLesAnciennesDettes() {
        // Commande clôturée : la même source, non réservée, paie d'abord sa commande puis l'ancienne vente.
        var a = CalculImputation.repartir(
                List.of(new Source("Acompte", 10000, "C1", null, false)),
                List.of(new Besoin("VENTE_OEUFS", "Ancienne", 20000, null)));
        assertEquals(10000, total(a));
        assertEquals("Ancienne", a.get(0).cibleUniqueId());
    }

    @Test
    void remboursementSansCommandeNePrendPasLArgentReserve() {
        var e = assertThrows(IllegalArgumentException.class, () -> CalculImputation.prelever(
                List.of(new Source("Libre", 1000, null, null, false),
                        new Source("Acompte", 5000, "C1", null, true)),
                "REMBOURSEMENT", "R1", 3000, null));
        assertTrue(e.getMessage().contains("réservés"));
        var a = CalculImputation.prelever(
                List.of(new Source("Libre", 1000, null, null, false),
                        new Source("Acompte", 5000, "C1", null, true)),
                "REMBOURSEMENT", "R1", 1000, null);
        assertEquals("Libre", a.get(0).paiementUniqueId());
    }

    @Test
    void remboursementDeLaCommandePeutPrendreSonAcompteReserve() {
        var a = CalculImputation.prelever(
                List.of(new Source("Libre", 1000, null, null, false),
                        new Source("AcompteC1", 5000, "C1", null, true),
                        new Source("AcompteC2", 5000, "C2", null, true)),
                "REMBOURSEMENT", "R1", 6000, "C1");
        assertEquals("AcompteC1", a.get(0).paiementUniqueId());
        assertEquals(5000, a.get(0).montant());
        assertEquals("Libre", a.get(1).paiementUniqueId());
        assertEquals(1000, a.get(1).montant());
        assertEquals(6000, total(a));
    }

    @Test
    void paiementALaLivraisonRegleSaLivraisonAvantLAcompte() {
        // Acompte 10 000 (plus ancien), livraison 8 000 payée 8 000 à la livraison : le
        // paiement de la livraison la règle, l'acompte reste entier pour la suite.
        var a = CalculImputation.repartir(
                List.of(new Source("Acompte", 10000, "C1", null, true),
                        new Source("PaieLivraison", 8000, "C1", "L1", true)),
                List.of(new Besoin("VENTE_OEUFS", "L1", 8000, "C1")));
        assertEquals(1, a.size());
        assertEquals("PaieLivraison", a.get(0).paiementUniqueId());
        assertEquals(8000, a.get(0).montant());
    }
}
