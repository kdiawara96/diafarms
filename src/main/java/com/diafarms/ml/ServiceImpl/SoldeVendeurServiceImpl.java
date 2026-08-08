package com.diafarms.ml.ServiceImpl;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.SoldeVendeurDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.SoldeVendeur;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.SoldeVendeurRepo;

import lombok.RequiredArgsConstructor;

// Petit service partagé par VenteOeufsImpl et VenteReformeImpl : les deux imputent
// l'écart théorique/rapporté d'une vente au même solde cumulé du vendeur (peu importe
// s'il vend des œufs ou des sujets réformés, c'est LUI qui doit ou est créditeur).
@Service
@RequiredArgsConstructor
public class SoldeVendeurServiceImpl {

    private final SoldeVendeurRepo repo;

    private SoldeVendeur findOrCreate(Utilisateurs vendeur, Farm farm) {
        return repo.findByVendeur_Id(vendeur.getId()).orElseGet(() -> {
            SoldeVendeur s = new SoldeVendeur();
            s.setUniqueId(java.util.UUID.randomUUID().toString());
            s.setVendeur(vendeur);
            s.setFarm(farm);
            s.setSolde(0.0);
            s.setInitialisation(Initialisation.init());
            return repo.save(s);
        });
    }

    /** delta > 0 = le vendeur doit plus (théorique > rapporté) ; delta < 0 = il rembourse. */
    @Transactional
    public void ajusterSolde(Utilisateurs vendeur, Farm farm, double delta) {
        if (vendeur == null || farm == null || delta == 0.0) return;
        SoldeVendeur s = findOrCreate(vendeur, farm);
        s.setSolde(s.getSolde() + delta);
        if (s.getInitialisation() != null) s.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        repo.save(s);
    }

    @Transactional(readOnly = true)
    public SoldeVendeurDTO getSolde(Utilisateurs vendeur) {
        if (vendeur == null) return SoldeVendeurDTO.builder().solde(0.0).build();
        double solde = repo.findByVendeur_Id(vendeur.getId()).map(SoldeVendeur::getSolde).orElse(0.0);
        return SoldeVendeurDTO.builder()
                .vendeurUniqueId(vendeur.getUniqueId())
                .vendeurNom(vendeur.getFullName())
                .solde(solde)
                .build();
    }

    @Transactional(readOnly = true)
    public List<SoldeVendeurDTO> listNonZero(Farm farm) {
        if (farm == null) return List.of();
        return repo.findAllNonZeroByFarmId(farm.getId()).stream()
                .map(s -> SoldeVendeurDTO.builder()
                        .vendeurUniqueId(s.getVendeur().getUniqueId())
                        .vendeurNom(s.getVendeur().getFullName())
                        .solde(s.getSolde())
                        .build())
                .toList();
    }
}
