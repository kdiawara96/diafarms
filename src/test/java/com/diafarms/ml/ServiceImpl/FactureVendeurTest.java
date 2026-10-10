package com.diafarms.ml.ServiceImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Facture;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.repository.CommandeRepo;
import com.diafarms.ml.repository.FactureLigneRepo;
import com.diafarms.ml.repository.FactureRepo;
import com.diafarms.ml.repository.PaiementClientRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.FactureGenerateRequest;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.MinioService;

// Le vendeur fait et voit seulement les factures de SES ventes ; la production n'y a pas accès.
class FactureVendeurTest {

    FactureRepo factureRepo = mock(FactureRepo.class);
    VenteOeufsRepo venteOeufsRepo = mock(VenteOeufsRepo.class);
    OtherService otherService = mock(OtherService.class);
    FactureServiceImpl service;
    Farm farm = new Farm();
    Utilisateurs vendeur, autreVendeur, producteur, admin;

    Utilisateurs user(long id, String... roles) {
        Utilisateurs u = new Utilisateurs();
        u.setId(id);
        u.setFarm(farm);
        java.util.Set<Roles> rs = new java.util.HashSet<>();
        for (String r : roles) { Roles x = new Roles(); x.setRole(r); rs.add(x); }
        u.setRoles(rs);
        return u;
    }

    @BeforeEach
    void init() {
        farm.setId(1L);
        vendeur = user(10, "VENTE");
        autreVendeur = user(11, "VENTE");
        producteur = user(12, "PRODUCTION");
        admin = user(13, "ADMIN");
        service = new FactureServiceImpl(factureRepo, mock(FactureLigneRepo.class), venteOeufsRepo,
                mock(VenteReformeRepo.class), mock(CommandeRepo.class), mock(CompteClientService.class),
                mock(PaiementClientService.class), mock(PaiementClientRepo.class), mock(LogsServices.class),
                otherService, mock(MinioService.class));
        when(factureRepo.search(any(), anyBoolean(), any(), anyBoolean(), anyString(), anyBoolean(), any(), any(Pageable.class)))
                .thenReturn((Page<Facture>) new PageImpl<Facture>(List.of()));
    }

    void connecte(Utilisateurs u) {
        when(otherService.getCurrentUser()).thenReturn(u);
    }

    FactureGenerateRequest demande(String venteUid) {
        FactureGenerateRequest r = new FactureGenerateRequest();
        FactureGenerateRequest.VenteRef ref = new FactureGenerateRequest.VenteRef();
        ref.setType("VENTE_OEUFS");
        ref.setUniqueId(venteUid);
        r.setVentes(List.of(ref));
        return r;
    }

    @Test
    void vendeurNePeutPasFacturerLaVenteDunAutre() {
        VenteOeufs v = new VenteOeufs();
        v.setUniqueId("V1");
        v.setFarm(farm);
        v.setInitialisation(Initialisation.init());
        v.setCreePar(autreVendeur);
        when(venteOeufsRepo.findByUniqueId("V1")).thenReturn(Optional.of(v));
        connecte(vendeur);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.genererDepuis(demande("V1")));
        assertEquals("Vous pouvez faire la facture de vos propres ventes seulement.", e.getMessage());
    }

    @Test
    void vendeurNeFacturePasUneCommande() {
        connecte(vendeur);
        FactureGenerateRequest r = new FactureGenerateRequest();
        r.setSourceType("COMMANDE");
        r.setSourceUniqueId("C1");
        assertThrows(IllegalArgumentException.class, () -> service.genererDepuis(r));
    }

    @Test
    void vendeurNeVoitQueSesFactures() {
        connecte(vendeur);
        service.list(0, 10, null, null);
        verify(factureRepo).search(eq(1L), eq(false), any(), eq(false), anyString(), eq(true), eq(10L), any(Pageable.class));
    }

    @Test
    void adminVoitTout() {
        connecte(admin);
        service.list(0, 10, null, null);
        verify(factureRepo).search(eq(1L), eq(false), any(), eq(false), anyString(), eq(false), eq(-1L), any(Pageable.class));
    }

    @Test
    void productionNeVoitPasLesFactures() {
        connecte(producteur);
        assertThrows(IllegalArgumentException.class, () -> service.list(0, 10, null, null));
    }

    @Test
    void vendeurNeTelechargePasLaFactureDunAutre() {
        Facture f = new Facture();
        f.setUniqueId("F1");
        f.setFarm(farm);
        f.setCreePar(autreVendeur);
        when(factureRepo.findByUniqueId("F1")).thenReturn(f);
        connecte(vendeur);
        assertThrows(IllegalArgumentException.class, () -> service.genererPdf("F1"));
    }

    @Test
    void vendeurNAnnulePas() {
        connecte(vendeur);
        assertThrows(IllegalArgumentException.class, () -> service.annuler("F1", "erreur"));
    }

    @SuppressWarnings("unused")
    private static Set<String> rien() { return Set.of(); }
}
