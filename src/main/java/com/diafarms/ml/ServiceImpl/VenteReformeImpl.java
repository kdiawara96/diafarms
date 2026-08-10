package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.StockReformeDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.DTO.VenteReformeRepartitionDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Magasin;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.models.VenteReformeRepartition;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.MagasinTransfertRepo;
import com.diafarms.ml.repository.MagasinRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.repository.VenteReformeRepartitionRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

// Vente réforme (Finance) : voir VenteOeufsImpl pour le détail du mécanisme — vendue
// DEPUIS un magasin précis, alimenté par des transferts explicites depuis un ou
// plusieurs projets (voir MagasinTransfert).
@Service
@RequiredArgsConstructor
public class VenteReformeImpl implements VenteReformeService {

    private final VenteReformeRepo venteReformeRepo;
    private final VenteReformeRepartitionRepo repartitionRepo;
    private final ReformeRepo reformeRepo;
    private final ProjetsRepo projetsRepo;
    private final MagasinRepo magasinRepo;
    private final MagasinTransfertRepo magasinTransfertRepo;
    private final SoldeVendeurServiceImpl soldeVendeurService;
    private final LogsServices logs;
    private final OtherService otherService;
    private final TransactionService transactionService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    private Map<Long, Integer> disponibleParProjetDansMagasin(Magasin magasin) {
        Map<Long, Integer> disponible = new LinkedHashMap<>();
        List<Long> projetIds = magasinTransfertRepo.findDistinctProjetIdsByMagasinAndType(magasin.getId(), TypeStockMagasin.REFORME);
        for (Long projetId : projetIds) {
            int transfere = nz(magasinTransfertRepo.sumQuantiteByMagasinAndProjetAndType(magasin.getId(), projetId, TypeStockMagasin.REFORME));
            int vendu = nz(repartitionRepo.sumSujetsByProjetIdAndMagasinId(projetId, magasin.getId()));
            int restant = transfere - vendu;
            if (restant > 0) disponible.put(projetId, restant);
        }
        return disponible;
    }

    private List<VenteReformeRepartition> repartirEtCreerTransactions(VenteReforme saved, Farm farm, int nombreSujets, double montant, Utilisateurs creePar) {
        Map<Long, Integer> disponible = disponibleParProjetDansMagasin(saved.getMagasin());
        Map<Long, Projets> projetsParId = new LinkedHashMap<>();
        for (Long projetId : disponible.keySet()) {
            projetsRepo.findById(projetId).ifPresent(p -> projetsParId.put(projetId, p));
        }

        List<RepartitionUtil.Part> parts = RepartitionUtil.repartir(nombreSujets, montant, disponible);
        List<VenteReformeRepartition> lignes = new java.util.ArrayList<>();

        // Traçabilité de l'écart directement dans la ligne — voir le commentaire
        // équivalent dans VenteOeufsImpl.repartirEtCreerTransactions.
        String suffixeEcart = suffixeEcartRapporte(saved.getMontant(), saved.getMontantRapporte());

        for (RepartitionUtil.Part part : parts) {
            Projets projet = projetsParId.get(part.projetId);

            VenteReformeRepartition r = new VenteReformeRepartition();
            r.setUniqueId(java.util.UUID.randomUUID().toString());
            r.setVenteReforme(saved);
            r.setProjet(projet);
            r.setNombreSujetsAttribue(part.quantite);
            r.setMontantAttribue(part.montant);
            lignes.add(repartitionRepo.save(r));

            transactionService.createFromSource(
                    projet, farm, part.montant, "Vente réforme", saved.getDate(),
                    "Vente réforme — " + part.quantite + " sujet(s) (part de " + saved.getNombreSujets() + " vendus, magasin " + saved.getMagasin().getNom() + ")" + suffixeEcart,
                    SourceTransaction.VENTE_REFORME, r.getUniqueId(), creePar
            );
        }
        return lignes;
    }

    /** " — Rapporté : X FCFA / Y FCFA théoriques (manque/surplus Z FCFA)", vide si pas
     * encore de montant rapporté saisi ou si égal au théorique — même helper que
     * VenteOeufsImpl (dupliqué, pas de base commune entre les deux services), même
     * convention de signe que SoldeVendeurServiceImpl.ajusterSolde. */
    private String suffixeEcartRapporte(Double montantTheorique, Double montantRapporte) {
        if (montantTheorique == null || montantRapporte == null || montantRapporte.equals(montantTheorique)) {
            return "";
        }
        double ecart = montantTheorique - montantRapporte;
        return String.format(Locale.FRANCE, " — Rapporté : %.0f FCFA / %.0f FCFA théoriques (%s %.0f FCFA)",
                montantRapporte, montantTheorique, ecart > 0 ? "manque" : "surplus", Math.abs(ecart));
    }

    @Override
    @Transactional
    public VenteReformeDTO create(VenteReformeCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Farm farm = currentUser.getFarm();

        if (data.getNombreSujets() == null || data.getNombreSujets() <= 0) {
            throw new IllegalArgumentException("Le nombre de sujets vendus doit être positif.");
        }
        if (data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant de la vente doit être positif.");
        }
        if (data.getMagasinUniqueId() == null || data.getMagasinUniqueId().isBlank()) {
            throw new IllegalArgumentException("Le magasin de vente est obligatoire.");
        }

        Magasin magasin = magasinRepo.findByUniqueId(data.getMagasinUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Magasin introuvable : " + data.getMagasinUniqueId()));
        if (magasin.getType() != Magasin.TypeMagasin.VENTE) {
            throw new IllegalArgumentException("On ne peut vendre que depuis un magasin de type VENTE.");
        }

        int restant = disponibleParProjetDansMagasin(magasin).values().stream().mapToInt(Integer::intValue).sum();
        if (data.getNombreSujets() > restant) {
            throw new IllegalArgumentException(
                "Stock de sujets réformés insuffisant dans ce magasin (" + restant + " sujet(s) restants)."
            );
        }

        VenteReforme v = new VenteReforme();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setFarm(farm);
        v.setMagasin(magasin);
        v.setCreePar(currentUser);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setNombreSujets(data.getNombreSujets());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        v.setMontantRapporte(data.getMontantRapporte());
        v.setInitialisation(Initialisation.init());

        VenteReforme saved = venteReformeRepo.save(v);

        List<VenteReformeRepartition> lignes = repartirEtCreerTransactions(saved, farm, data.getNombreSujets(), data.getMontant(), currentUser);

        if (data.getMontantRapporte() != null) {
            soldeVendeurService.ajusterSolde(currentUser, farm, data.getMontant() - data.getMontantRapporte());
        }

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme",
                "Vente réforme de " + saved.getNombreSujets() + " sujet(s) (" + saved.getMontant() + " FCFA) depuis " + magasin.getNom() + ", répartie entre les projets contributeurs");

        VenteReformeDTO dto = VenteReformeDTO.fromEntity(saved);
        dto.setRepartitions(lignes.stream().map(VenteReformeRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public VenteReformeDTO update(String uniqueId, VenteReformeUpdate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));

        Double ancienMontant = v.getMontant();
        Double ancienMontantRapporte = v.getMontantRapporte();

        if (data.getDate() != null) v.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) v.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getPrixUnitaire() != null) v.setPrixUnitaire(data.getPrixUnitaire());

        boolean redistribuer = data.getNombreSujets() != null || data.getMontant() != null;

        if (data.getNombreSujets() != null) {
            if (data.getNombreSujets() <= 0) {
                throw new IllegalArgumentException("Le nombre de sujets vendus doit être positif.");
            }
            if (v.getMagasin() == null) {
                throw new IllegalArgumentException("Cette vente n'est rattachée à aucun magasin (ancienne vente farm-wide) : quantité non modifiable.");
            }
            Map<Long, Integer> disponible = disponibleParProjetDansMagasin(v.getMagasin());
            int restantHorsCetteVente = disponible.values().stream().mapToInt(Integer::intValue).sum() + nz(v.getNombreSujets());
            if (data.getNombreSujets() > restantHorsCetteVente) {
                throw new IllegalArgumentException(
                    "Stock de sujets réformés insuffisant dans ce magasin (" + restantHorsCetteVente + " sujet(s) restants)."
                );
            }
            v.setNombreSujets(data.getNombreSujets());
        }
        if (data.getMontant() != null) {
            if (data.getMontant() <= 0) {
                throw new IllegalArgumentException("Le montant de la vente doit être positif.");
            }
            v.setMontant(data.getMontant());
        }

        if (data.getMontantRapporte() != null) {
            v.setMontantRapporte(data.getMontantRapporte());
        }

        boolean ecartChange = data.getMontantRapporte() != null || data.getMontant() != null;
        if (ecartChange && v.getCreePar() != null) {
            if (ancienMontantRapporte != null) {
                soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), -(nz(ancienMontant) - ancienMontantRapporte));
            }
            if (v.getMontantRapporte() != null) {
                soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), nz(v.getMontant()) - v.getMontantRapporte());
            }
        }

        if (v.getInitialisation() != null) {
            v.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        VenteReforme saved = venteReformeRepo.save(v);

        List<VenteReformeRepartition> lignesActuelles;
        if (redistribuer) {
            List<VenteReformeRepartition> anciennes = repartitionRepo.findByVenteReforme_UniqueId(saved.getUniqueId());
            for (VenteReformeRepartition ancienne : anciennes) {
                transactionService.toggleRemovedBySource(ancienne.getUniqueId());
            }
            repartitionRepo.deleteAll(anciennes);
            lignesActuelles = repartirEtCreerTransactions(saved, saved.getFarm(), saved.getNombreSujets(), saved.getMontant(), saved.getCreePar() != null ? saved.getCreePar() : currentUser);
        } else {
            lignesActuelles = repartitionRepo.findByVenteReforme_UniqueId(saved.getUniqueId());
            // Pas de redistribution, mais l'écart rapporté a pu changer — voir le
            // commentaire équivalent dans VenteOeufsImpl.update().
            if (ecartChange) {
                String suffixeEcart = suffixeEcartRapporte(saved.getMontant(), saved.getMontantRapporte());
                for (VenteReformeRepartition ligne : lignesActuelles) {
                    transactionService.updateDescriptionBySource(ligne.getUniqueId(),
                            "Vente réforme — " + ligne.getNombreSujetsAttribue() + " sujet(s) (part de " + saved.getNombreSujets() + " vendus, magasin " + saved.getMagasin().getNom() + ")" + suffixeEcart);
                }
            }
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme", "Modification d'une vente réforme");
        }

        VenteReformeDTO dto = VenteReformeDTO.fromEntity(saved);
        dto.setRepartitions(lignesActuelles.stream().map(VenteReformeRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));

        v.getInitialisation().setRemoved(!v.getInitialisation().getRemoved());
        venteReformeRepo.save(v);
        boolean removed = v.getInitialisation().getRemoved();

        for (VenteReformeRepartition r : repartitionRepo.findByVenteReforme_UniqueId(uniqueId)) {
            transactionService.toggleRemovedBySource(r.getUniqueId());
        }

        if (v.getCreePar() != null && v.getMontantRapporte() != null) {
            double ecart = nz(v.getMontant()) - v.getMontantRapporte();
            soldeVendeurService.ajusterSolde(v.getCreePar(), v.getFarm(), removed ? -ecart : ecart);
        }

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteReforme",
                    (removed ? "Suppression" : "Restauration") + " d'une vente réforme");
        }

        return removed ? "Vente supprimée." : "Vente récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<VenteReformeDTO> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        Page<VenteReforme> resultPage = venteReformeRepo.search(farmId, pageable);

        List<VenteReformeDTO> dtoList = resultPage.getContent().stream()
                .map(VenteReformeDTO::fromEntity)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }

    @Override
    @Transactional(readOnly = true)
    public StockReformeDTO getStock() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Long farmId = currentUser.getFarm().getId();

        // Indicateur farm-wide global (reporting admin), distinct du stock par magasin
        // qui seul plafonne une vente précise — voir VenteOeufsImpl.getStock.
        int totalReforme = nz(reformeRepo.sumSujetsByFarmId(farmId));
        int totalVendu = nz(venteReformeRepo.sumSujetsVendusByFarmId(farmId));
        int restant = totalReforme - totalVendu;

        return StockReformeDTO.builder()
                .totalReforme(totalReforme)
                .totalVendu(totalVendu)
                .stockRestant(restant)
                .statut(restant <= 0 ? "EPUISE" : "ACTIF")
                .build();
    }
}
