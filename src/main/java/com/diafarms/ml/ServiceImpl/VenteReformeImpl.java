package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
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
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.models.VenteReformeRepartition;
import com.diafarms.ml.others.PaginatedResponse;
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

// Vente réforme (Finance) : voir VenteOeufsImpl pour le détail du mécanisme —
// plafonnée par le total réformé (Reforme, Production) de TOUTE LA FERME moins déjà
// vendu, répartie automatiquement au prorata du nombre de sujets réformés
// disponibles de chaque projet contributeur.
@Service
@RequiredArgsConstructor
public class VenteReformeImpl implements VenteReformeService {

    private final VenteReformeRepo venteReformeRepo;
    private final VenteReformeRepartitionRepo repartitionRepo;
    private final ReformeRepo reformeRepo;
    private final ProjetsRepo projetsRepo;
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

    private int stockFermeRestant(Long farmId) {
        int totalReforme = nz(reformeRepo.sumSujetsByFarmId(farmId));
        int totalVendu = nz(venteReformeRepo.sumSujetsVendusByFarmId(farmId));
        return totalReforme - totalVendu;
    }

    private Map<Long, Integer> disponibleParProjet(List<Projets> projets) {
        Map<Long, Integer> disponible = new LinkedHashMap<>();
        for (Projets p : projets) {
            int reforme = nz(reformeRepo.sumSujetsByProjetId(p.getId()));
            int vendu = nz(repartitionRepo.sumSujetsByProjetId(p.getId()));
            int restant = reforme - vendu;
            if (restant > 0) disponible.put(p.getId(), restant);
        }
        return disponible;
    }

    private List<VenteReformeRepartition> repartirEtCreerTransactions(VenteReforme saved, Farm farm, int nombreSujets, double montant) {
        List<Projets> projetsActifs = projetsRepo.findAllActiveByFarm(farm.getId());
        Map<Long, Integer> disponible = disponibleParProjet(projetsActifs);
        Map<Long, Projets> projetsParId = projetsActifs.stream()
                .collect(java.util.stream.Collectors.toMap(Projets::getId, p -> p));

        List<RepartitionUtil.Part> parts = RepartitionUtil.repartir(nombreSujets, montant, disponible);
        List<VenteReformeRepartition> lignes = new java.util.ArrayList<>();

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
                    "Vente réforme — " + part.quantite + " sujet(s) (part de " + saved.getNombreSujets() + " vendus)",
                    SourceTransaction.VENTE_REFORME, r.getUniqueId()
            );
        }
        return lignes;
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

        int restant = stockFermeRestant(farm.getId());
        if (data.getNombreSujets() > restant) {
            throw new IllegalArgumentException(
                "Stock de sujets réformés insuffisant pour la ferme (" + restant + " sujet(s) restants)."
            );
        }

        VenteReforme v = new VenteReforme();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setFarm(farm);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setNombreSujets(data.getNombreSujets());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        v.setInitialisation(Initialisation.init());

        VenteReforme saved = venteReformeRepo.save(v);

        List<VenteReformeRepartition> lignes = repartirEtCreerTransactions(saved, farm, data.getNombreSujets(), data.getMontant());

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme",
                "Vente réforme de " + saved.getNombreSujets() + " sujet(s) (" + saved.getMontant() + " FCFA), répartie entre les projets contributeurs");

        VenteReformeDTO dto = VenteReformeDTO.fromEntity(saved);
        dto.setRepartitions(lignes.stream().map(VenteReformeRepartitionDTO::fromEntity).toList());
        return dto;
    }

    @Override
    @Transactional
    public VenteReformeDTO update(String uniqueId, VenteReformeUpdate data) {
        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));

        if (data.getDate() != null) v.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) v.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getPrixUnitaire() != null) v.setPrixUnitaire(data.getPrixUnitaire());

        boolean redistribuer = data.getNombreSujets() != null || data.getMontant() != null;

        if (data.getNombreSujets() != null) {
            if (data.getNombreSujets() <= 0) {
                throw new IllegalArgumentException("Le nombre de sujets vendus doit être positif.");
            }
            Long farmId = v.getFarm().getId();
            int totalReforme = nz(reformeRepo.sumSujetsByFarmId(farmId));
            int totalVendu = nz(venteReformeRepo.sumSujetsVendusByFarmId(farmId));
            int ancienNombre = nz(v.getNombreSujets());
            int nouveauTotalVendu = totalVendu - ancienNombre + data.getNombreSujets();
            if (nouveauTotalVendu > totalReforme) {
                throw new IllegalArgumentException(
                    "Stock de sujets réformés insuffisant pour la ferme (" + (totalReforme - (totalVendu - ancienNombre)) + " sujet(s) restants)."
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
            lignesActuelles = repartirEtCreerTransactions(saved, saved.getFarm(), saved.getNombreSujets(), saved.getMontant());
        } else {
            lignesActuelles = repartitionRepo.findByVenteReforme_UniqueId(saved.getUniqueId());
        }

        Utilisateurs currentUser = getCurrentUserSafe();
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
