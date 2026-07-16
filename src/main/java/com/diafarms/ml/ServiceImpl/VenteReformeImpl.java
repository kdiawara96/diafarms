package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.StockReformeDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

// Vente réforme (Finance) : acte commercial, PAS une saisie Production — pas
// rattachée à un projet précis, plafonnée par le total réformé (Reforme, Production)
// de TOUTE LA FERME de l'utilisateur courant, moins ce qui a déjà été vendu.
@Service
@RequiredArgsConstructor
public class VenteReformeImpl implements VenteReformeService {

    private final VenteReformeRepo venteReformeRepo;
    private final ReformeRepo reformeRepo;
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

    private int stockRestant(Long farmId) {
        int totalReforme = nz(reformeRepo.sumSujetsByFarmId(farmId));
        int totalVendu = nz(venteReformeRepo.sumSujetsVendusByFarmId(farmId));
        return totalReforme - totalVendu;
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

        int restant = stockRestant(farm.getId());
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

        transactionService.createFromSource(
                null, farm, saved.getMontant(), "Vente réforme", saved.getDate(),
                "Vente réforme — " + saved.getNombreSujets() + " sujet(s)",
                SourceTransaction.VENTE_REFORME, saved.getUniqueId(),
                data.getProjetsConcernesUniqueIds()
        );

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme",
                "Vente réforme de " + saved.getNombreSujets() + " sujet(s) (" + saved.getMontant() + " FCFA)");

        return VenteReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public VenteReformeDTO update(String uniqueId, VenteReformeUpdate data) {
        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));

        if (data.getDate() != null) v.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) v.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getNombreSujets() != null) {
            if (data.getNombreSujets() <= 0) {
                throw new IllegalArgumentException("Le nombre de sujets vendus doit être positif.");
            }
            Long farmId = v.getFarm().getId();
            int totalReforme = nz(reformeRepo.sumSujetsByFarmId(farmId));
            int totalVendu = nz(venteReformeRepo.sumSujetsVendusByFarmId(farmId));
            int nouveauTotalVendu = totalVendu - v.getNombreSujets() + data.getNombreSujets();
            if (nouveauTotalVendu > totalReforme) {
                throw new IllegalArgumentException(
                    "Stock de sujets réformés insuffisant pour la ferme (" + (totalReforme - (totalVendu - v.getNombreSujets())) + " sujet(s) restants)."
                );
            }
            v.setNombreSujets(data.getNombreSujets());
        }
        if (data.getPrixUnitaire() != null) v.setPrixUnitaire(data.getPrixUnitaire());
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
        transactionService.updateMontantBySource(saved.getUniqueId(), saved.getMontant());

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme", "Modification d'une vente réforme");
        }

        return VenteReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        VenteReforme v = venteReformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente réforme introuvable : " + uniqueId));

        v.getInitialisation().setRemoved(!v.getInitialisation().getRemoved());
        venteReformeRepo.save(v);
        boolean removed = v.getInitialisation().getRemoved();
        transactionService.toggleRemovedBySource(v.getUniqueId());

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
