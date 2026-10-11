package com.diafarms.ml.ServiceImpl;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.diafarms.ml.DTO.CompteClientDTO;
import com.diafarms.ml.models.Alimentation;

// Règles pures ajoutées pour la cohérence des rapports (PDF projet, Dû clients,
// transfert de stock à la clôture).
class RapportsCoherenceTest {

    private static final LocalDate J1 = LocalDate.of(2026, 10, 1);

    @Test
    void effectifVivantJourParJourCompteLesSortiesAvantLaPeriode() {
        // 100 sujets ; 5 morts le 28/09 (avant la période), 3 réformés le 02/10, 2 morts le 03/10.
        Map<LocalDate, Integer> sorties = Map.of(J1.minusDays(3), 5, J1.plusDays(1), 3, J1.plusDays(2), 2);
        Map<LocalDate, Integer> v = ProjetRapportPdfServiceImpl.effectifsParJour(100, sorties, J1, J1.plusDays(3));
        assertEquals(List.of(95, 92, 90, 90), List.copyOf(v.values()));
    }

    @Test
    void effectifVivantJamaisNegatif() {
        Map<LocalDate, Integer> v = ProjetRapportPdfServiceImpl.effectifsParJour(10, Map.of(J1, 15), J1, J1);
        assertEquals(0, v.get(J1));
    }

    @Test
    void transfertStockReconnuParSonNom() {
        assertTrue(AlimentationImpl.estTransfertStock(AlimentationImpl.nomTransfertDepuis("P-001")));
        assertTrue(AlimentationImpl.estTransfertStock("Transfert depuis P-002"));
        assertFalse(AlimentationImpl.estTransfertStock("Aliment ponte"));
        assertFalse(AlimentationImpl.estTransfertStock(null));
    }

    @Test
    void consommationDeTransfertMemeJourMemeQuantite() {
        Alimentation entree = new Alimentation();
        entree.setDateDistribution(J1);
        entree.setQuantiteKg(123.5);
        List<Alimentation> entrees = List.of(entree);
        assertTrue(AlimentationImpl.estConsommationDeTransfert(J1, 123.5, entrees));
        assertFalse(AlimentationImpl.estConsommationDeTransfert(J1, 50.0, entrees));
        assertFalse(AlimentationImpl.estConsommationDeTransfert(J1.plusDays(1), 123.5, entrees));
        assertFalse(AlimentationImpl.estConsommationDeTransfert(J1, 123.5, List.of()));
    }

    @Test
    void duNeReutilisePasUnAcompteReserve() {
        // Reste 50 000 sur des ventes livrées ; 30 000 d'acompte réservé à une commande pas livrée.
        CompteClientDTO c = CompteClientDTO.builder().resteAPayer(50000).avance(30000)
                .avanceReservee(30000).avanceLibre(0).build();
        assertEquals(50000, CompteClientService.du(c));
    }

    @Test
    void duDiminueAvecLAvanceLibreSansDevenirNegatif() {
        CompteClientDTO a = CompteClientDTO.builder().resteAPayer(50000).avanceLibre(20000).build();
        CompteClientDTO b = CompteClientDTO.builder().resteAPayer(10000).avanceLibre(40000).build();
        assertEquals(30000, CompteClientService.du(a));
        assertEquals(0, CompteClientService.du(b));
        assertEquals(30000, CompteClientService.sommeDu(List.of(a, b)));
    }
}
