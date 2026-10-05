package com.diafarms.ml.commons;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.diafarms.ml.DTO.AbonnementTarifDTO;
import com.diafarms.ml.ServiceImpl.AbonnementTarifService;
import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.AbonnementConfig;
import com.diafarms.ml.repository.AbonnementConfigRepo;

class AbonnementTarifTest {

    private static final AbonnementTarif.Regles DEFAUT = AbonnementTarif.regles(null);

    private static double mois(int poules) {
        return AbonnementTarif.calculer(poules, null, null, DEFAUT, false).prixMensuel();
    }

    @Test
    void reglePrixParPoule() {
        assertEquals(5000, mois(0));
        assertEquals(5000, mois(500));
        assertEquals(5100, mois(834));   // 5 004 arrondi au-dessus
        assertEquals(6000, mois(1000));
        assertEquals(8100, mois(1350));
        assertEquals(12000, mois(2000));
        assertEquals(30000, mois(5000));
        assertEquals(60000, mois(10000));
        assertEquals(81000, AbonnementTarif.calculer(1350, null, null, DEFAUT, false).prixAnnuel());
    }

    @Test
    void reglagesVidesOuInvalidesDonnentLesDefauts() {
        AbonnementConfig c = new AbonnementConfig();
        c.setMoisOffertsAnnuel(15);
        c.setArrondi(0);
        AbonnementTarif.Regles r = AbonnementTarif.regles(c);
        assertEquals(6, r.prixParPoule());
        assertEquals(5000, r.prixMinimumMensuel());
        assertEquals(2, r.moisOffertsAnnuel());
        assertEquals(100, r.arrondi());
    }

    @Test
    void prixFixeRemplaceLaRegle() {
        Abonnement a = new Abonnement();
        a.setPrixMensuelFixe(4000.0);
        a.setMotifPrixFixe("Premier client");
        AbonnementTarifDTO t = AbonnementTarif.calculer(2000, null, a, DEFAUT, false);
        assertTrue(t.prixFixe());
        assertEquals(4000, t.prixMensuel());
        assertEquals(40000, t.prixAnnuel());
        assertEquals(12000, t.prixMensuelSelonPoules());
        assertEquals("Montant : 4 000 FCFA par mois (tarif spécial) ou 40 000 FCFA par an.", AbonnementTarif.phraseMontant(t));
    }

    @Test
    void phraseDesRappels() {
        assertEquals("Montant : 8 100 FCFA par mois (1 350 poules) ou 81 000 FCFA par an.",
                AbonnementTarif.phraseMontant(AbonnementTarif.calculer(1350, null, null, DEFAUT, false)));
        assertEquals("Montant : 5 000 FCFA par mois (1 poule, prix minimum) ou 50 000 FCFA par an.",
                AbonnementTarif.phraseMontant(AbonnementTarif.calculer(1, null, null, DEFAUT, false)));
    }

    // Comptage en échec (base indisponible...) : jamais d'exception, prix minimum.
    @Test
    void comptageEnEchecDonneLeMinimum() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        org.mockito.Mockito.doThrow(new RuntimeException("base indisponible"))
                .when(jdbc).execute(any(org.springframework.jdbc.core.ConnectionCallback.class));
        AbonnementConfigRepo repo = mock(AbonnementConfigRepo.class);
        AbonnementTarifService service = new AbonnementTarifService(jdbc, repo);

        AbonnementTarifDTO t = service.tarifFerme(1L, null, null);
        assertTrue(t.calculEnErreur());
        assertEquals(5000, t.prixMensuel());
        assertEquals(50000, t.prixAnnuel());

        Abonnement a = new Abonnement();
        a.setPrixMensuelFixe(7000.0);
        Map<Long, AbonnementTarifDTO> m = service.tarifsFermes(List.of(1L, 2L), Map.of(2L, a), null);
        assertEquals(5000, m.get(1L).prixMensuel());
        assertEquals(7000, m.get(2L).prixMensuel());
        assertTrue(m.get(1L).calculEnErreur());
    }
}
