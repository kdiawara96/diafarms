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

import com.diafarms.ml.DTO.StockOeufsDTO;
import com.diafarms.ml.DTO.VenteOeufsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.update.VenteOeufsUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteOeufsService;

import lombok.RequiredArgsConstructor;

// Vente d'œufs (Finance) : acte commercial, PAS une saisie Production — pas rattachée
// à un projet précis, plafonnée par le total collecté (CollecteOeufs, Production) de
// TOUTE LA FERME de l'utilisateur courant, moins ce qui a déjà été vendu.
@Service
@RequiredArgsConstructor
public class VenteOeufsImpl implements VenteOeufsService {

    private final VenteOeufsRepo venteOeufsRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;
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
        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteByFarmId(farmId));
        return (totalCollecte - totalCasse) - totalVendu;
    }

    @Override
    @Transactional
    public VenteOeufsDTO create(VenteOeufsCreate data) {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Farm farm = currentUser.getFarm();

        if (data.getQuantiteOeufs() == null || data.getQuantiteOeufs() <= 0) {
            throw new IllegalArgumentException("La quantité d'œufs vendus doit être positive.");
        }
        if (data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant de la vente doit être positif.");
        }

        int restant = stockRestant(farm.getId());
        if (data.getQuantiteOeufs() > restant) {
            throw new IllegalArgumentException(
                "Stock d'œufs insuffisant pour la ferme (" + restant + " œuf(s) restants)."
            );
        }

        VenteOeufs v = new VenteOeufs();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setFarm(farm);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setQuantiteOeufs(data.getQuantiteOeufs());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        v.setInitialisation(Initialisation.init());

        VenteOeufs saved = venteOeufsRepo.save(v);

        transactionService.createFromSource(
                null, farm, saved.getMontant(), "Vente œufs", saved.getDate(),
                "Vente de " + saved.getQuantiteOeufs() + " œufs",
                SourceTransaction.VENTE_OEUFS, saved.getUniqueId(),
                data.getProjetsConcernesUniqueIds()
        );

        logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs",
                "Vente de " + saved.getQuantiteOeufs() + " œufs (" + saved.getMontant() + " FCFA)");

        return VenteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public VenteOeufsDTO update(String uniqueId, VenteOeufsUpdate data) {
        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));

        if (data.getDate() != null) v.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) v.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getQuantiteOeufs() != null) {
            if (data.getQuantiteOeufs() <= 0) {
                throw new IllegalArgumentException("La quantité d'œufs vendus doit être positive.");
            }
            Long farmId = v.getFarm().getId();
            int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
            int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
            int totalVendu = nz(venteOeufsRepo.sumQuantiteByFarmId(farmId));
            int nouveauTotalVendu = totalVendu - v.getQuantiteOeufs() + data.getQuantiteOeufs();
            if (nouveauTotalVendu > (totalCollecte - totalCasse)) {
                throw new IllegalArgumentException(
                    "Stock d'œufs insuffisant pour la ferme (" + ((totalCollecte - totalCasse) - (totalVendu - v.getQuantiteOeufs())) + " œuf(s) restants)."
                );
            }
            v.setQuantiteOeufs(data.getQuantiteOeufs());
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

        VenteOeufs saved = venteOeufsRepo.save(v);
        transactionService.updateMontantBySource(saved.getUniqueId(), saved.getMontant());

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs", "Modification d'une vente d'œufs");
        }

        return VenteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        VenteOeufs v = venteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Vente d'œufs introuvable : " + uniqueId));

        v.getInitialisation().setRemoved(!v.getInitialisation().getRemoved());
        venteOeufsRepo.save(v);
        boolean removed = v.getInitialisation().getRemoved();
        transactionService.toggleRemovedBySource(v.getUniqueId());

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), v.getId(), "VenteOeufs",
                    (removed ? "Suppression" : "Restauration") + " d'une vente d'œufs");
        }

        return removed ? "Vente supprimée." : "Vente récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<VenteOeufsDTO> list(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        Page<VenteOeufs> resultPage = venteOeufsRepo.search(farmId, pageable);

        List<VenteOeufsDTO> dtoList = resultPage.getContent().stream()
                .map(VenteOeufsDTO::fromEntity)
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
    public StockOeufsDTO getStock() {
        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser == null || currentUser.getFarm() == null) {
            throw new IllegalArgumentException("Utilisateur ou ferme introuvable.");
        }
        Long farmId = currentUser.getFarm().getId();

        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByFarmId(farmId));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByFarmId(farmId));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteByFarmId(farmId));
        int restant = (totalCollecte - totalCasse) - totalVendu;

        return StockOeufsDTO.builder()
                .totalCollecte(totalCollecte)
                .totalCasse(totalCasse)
                .totalVendu(totalVendu)
                .stockRestant(restant)
                .statut(restant <= 0 ? "EPUISE" : "ACTIF")
                .build();
    }
}
