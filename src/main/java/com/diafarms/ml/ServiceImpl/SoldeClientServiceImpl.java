package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.SoldeClientDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.SoldeClient;
import com.diafarms.ml.repository.SoldeClientRepo;

import lombok.RequiredArgsConstructor;

// Petit service partagé par VenteOeufsImpl et VenteReformeImpl — même principe que
// SoldeVendeurServiceImpl, mais utilisé uniquement quand une vente a un client
// identifié (voir VenteOeufs/VenteReforme.client) : l'écart théorique/rapporté est
// alors imputé au client (vente à crédit), pas au vendeur.
@Service
@RequiredArgsConstructor
public class SoldeClientServiceImpl {

    private final SoldeClientRepo repo;

    private SoldeClient findOrCreate(Client client, Farm farm) {
        return repo.findByClient_Id(client.getId()).orElseGet(() -> {
            SoldeClient s = new SoldeClient();
            s.setUniqueId(java.util.UUID.randomUUID().toString());
            s.setClient(client);
            s.setFarm(farm);
            s.setSolde(0.0);
            s.setInitialisation(Initialisation.init());
            return repo.save(s);
        });
    }

    /** delta > 0 = le client doit plus (théorique > rapporté) ; delta < 0 = il paie. */
    @Transactional
    public void ajusterSolde(Client client, Farm farm, double delta) {
        if (client == null || farm == null || delta == 0.0) return;
        SoldeClient s = findOrCreate(client, farm);
        s.setSolde(s.getSolde() + delta);
        if (s.getInitialisation() != null) s.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        repo.save(s);
    }

    @Transactional(readOnly = true)
    public SoldeClientDTO getSolde(Client client) {
        if (client == null) return SoldeClientDTO.builder().solde(0.0).build();
        double solde = repo.findByClient_Id(client.getId()).map(SoldeClient::getSolde).orElse(0.0);
        return SoldeClientDTO.builder()
                .clientUniqueId(client.getUniqueId())
                .clientNom(client.getNom())
                .solde(solde)
                .build();
    }

    @Transactional(readOnly = true)
    public List<SoldeClientDTO> listNonZero(Farm farm) {
        if (farm == null) return List.of();
        return repo.findAllNonZeroByFarmId(farm.getId()).stream()
                .map(s -> SoldeClientDTO.builder()
                        .clientUniqueId(s.getClient().getUniqueId())
                        .clientNom(s.getClient().getNom())
                        .solde(s.getSolde())
                        .build())
                .toList();
    }
}
