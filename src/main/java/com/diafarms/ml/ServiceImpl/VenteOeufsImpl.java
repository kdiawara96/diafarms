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
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteOeufs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.VenteOeufsRepo;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.update.VenteOeufsUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteOeufsService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VenteOeufsImpl implements VenteOeufsService {

    private final VenteOeufsRepo venteOeufsRepo;
    private final CollecteOeufsRepo collecteOeufsRepo;
    private final ProjetsRepo projetsRepo;
    private final BatimentRepo batimentRepo;
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

    private int stockRestant(Long projetId) {
        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByProjetId(projetId));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByProjetId(projetId));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteByProjetId(projetId));
        return (totalCollecte - totalCasse) - totalVendu;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    @Override
    @Transactional
    public VenteOeufsDTO create(VenteOeufsCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        if (data.getQuantiteOeufs() == null || data.getQuantiteOeufs() <= 0) {
            throw new IllegalArgumentException("La quantité d'œufs vendus doit être positive.");
        }
        if (data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant de la vente doit être positif.");
        }

        int restant = stockRestant(projet.getId());
        if (data.getQuantiteOeufs() > restant) {
            throw new IllegalArgumentException(
                "Stock d'œufs insuffisant pour ce projet (" + restant + " œuf(s) restants)."
            );
        }

        Utilisateurs currentUser = getCurrentUserSafe();

        VenteOeufs v = new VenteOeufs();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setProjet(projet);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setQuantiteOeufs(data.getQuantiteOeufs());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        v.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            v.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            v.setFarm(currentUser.getFarm());
        }

        VenteOeufs saved = venteOeufsRepo.save(v);

        transactionService.createFromSource(
                projet, saved.getMontant(), "Vente œufs", saved.getDate(),
                "Vente de " + saved.getQuantiteOeufs() + " œufs",
                SourceTransaction.VENTE_OEUFS, saved.getUniqueId()
        );

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteOeufs",
                    "Vente de " + saved.getQuantiteOeufs() + " œufs (" + saved.getMontant() + " FCFA) pour le projet '" + projet.getTitre() + "'");
        }

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
            Long projetId = v.getProjet().getId();
            int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByProjetId(projetId));
            int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByProjetId(projetId));
            int totalVendu = nz(venteOeufsRepo.sumQuantiteByProjetId(projetId));
            int nouveauTotalVendu = totalVendu - v.getQuantiteOeufs() + data.getQuantiteOeufs();
            if (nouveauTotalVendu > (totalCollecte - totalCasse)) {
                throw new IllegalArgumentException(
                    "Stock d'œufs insuffisant pour ce projet (" + ((totalCollecte - totalCasse) - (totalVendu - v.getQuantiteOeufs())) + " œuf(s) restants)."
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
        if (data.getBatimentUniqueId() != null) {
            v.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
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
    public PaginatedResponse<VenteOeufsDTO> list(int page, int size, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<VenteOeufs> resultPage = venteOeufsRepo.search(farmId, projetParam, batimentParam, pageable);

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
    public StockOeufsDTO getStock(String projetUniqueId) {
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));

        int totalCollecte = nz(collecteOeufsRepo.sumOeufsCollectesByProjetId(projet.getId()));
        int totalCasse = nz(collecteOeufsRepo.sumOeufsCassesByProjetId(projet.getId()));
        int totalVendu = nz(venteOeufsRepo.sumQuantiteByProjetId(projet.getId()));
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
