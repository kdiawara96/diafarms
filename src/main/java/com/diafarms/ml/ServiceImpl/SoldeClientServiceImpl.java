package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.CompteClientDTO;
import com.diafarms.ml.DTO.SoldeClientDTO;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.repository.ClientRepo;

import lombok.RequiredArgsConstructor;

// Petit service partagé par VenteOeufsImpl et VenteReformeImpl — même principe que
// SoldeVendeurServiceImpl, mais utilisé uniquement quand une vente a un client
// identifié (voir VenteOeufs/VenteReforme.client) : l'écart théorique/rapporté est
// alors imputé au client (vente à crédit), pas au vendeur.
// Le solde n'est plus stocké : il est entièrement recalculé par CompteClientService
// à partir des ventes, paiements et imputations actifs.
@Service
@RequiredArgsConstructor
public class SoldeClientServiceImpl {

    private final ClientRepo clientRepo;
    private final CompteClientService compteClientService;

    /** @deprecated Le solde est désormais calculé (CompteClientService) : plus aucun appel ne doit l'ajuster. */
    @Deprecated
    @Transactional
    public void ajusterSolde(Client client, Farm farm, double delta) {
        // Le solde est désormais calculé (CompteClientService) : plus aucun appel ne doit l'ajuster.
    }

    @Transactional(readOnly = true)
    public SoldeClientDTO getSolde(Client client) {
        if (client == null) return SoldeClientDTO.builder().solde(0.0).build();
        CompteClientDTO compte = compteClientService.compte(client);
        return SoldeClientDTO.builder()
                .clientUniqueId(compte.getClientUniqueId())
                .clientNom(compte.getClientNom())
                .solde(compte.getSolde())
                .build();
    }

    @Transactional(readOnly = true)
    public List<SoldeClientDTO> listNonZero(Farm farm) {
        if (farm == null) return List.of();
        return clientRepo.findAllActiveByFarmId(farm.getId()).stream()
                .map(compteClientService::compte)
                .filter(compte -> compte.getSolde() != 0.0)
                .map(compte -> SoldeClientDTO.builder()
                        .clientUniqueId(compte.getClientUniqueId())
                        .clientNom(compte.getClientNom())
                        .solde(compte.getSolde())
                        .build())
                .toList();
    }

    // Comptabilité, Reporting : total des créances (soldes positifs) des clients actifs de la ferme.
    @Transactional(readOnly = true)
    public double sumSoldePositif(Farm farm) {
        if (farm == null) return 0.0;
        return clientRepo.findAllActiveByFarmId(farm.getId()).stream()
                .map(compteClientService::compte)
                .mapToDouble(CompteClientDTO::getSolde)
                .filter(solde -> solde > 0.0)
                .sum();
    }

    // Comptabilité, Reporting : total des avances (paiements non encore imputés) des clients actifs de la ferme.
    @Transactional(readOnly = true)
    public double sumAvances(Farm farm) {
        if (farm == null) return 0.0;
        return clientRepo.findAllActiveByFarmId(farm.getId()).stream()
                .map(compteClientService::compte)
                .mapToDouble(CompteClientDTO::getAvance)
                .filter(avance -> avance > 0.0)
                .sum();
    }
}
