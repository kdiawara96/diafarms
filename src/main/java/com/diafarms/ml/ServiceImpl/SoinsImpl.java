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

import com.diafarms.ml.DTO.SoinsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Soins;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.SoinsRepo;
import com.diafarms.ml.request.create.SoinsCreate;
import com.diafarms.ml.request.update.SoinsUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.SoinsService;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class SoinsImpl implements SoinsService {

    private final SoinsRepo soinsRepo;
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

    // Voir AlimentationImpl.syncTransaction — même principe pour les soins.
    private void syncTransaction(Soins s, Utilisateurs currentUser) {
        if (currentUser == null || currentUser.getFarm() == null) return;
        String description = "Soins (" + s.getType() + " — " + s.getProduit() + ") — projet "
                + (s.getProjet() != null ? s.getProjet().getTitre() : "?");
        transactionService.syncSortie(s.getProjet(), currentUser.getFarm(), s.getCoutTotal(), "Soins",
                s.getDate(), description, SourceTransaction.SOINS, s.getUniqueId(), currentUser);
    }

    @Override
    @Transactional
    public SoinsDTO create(SoinsCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        Utilisateurs currentUser = getCurrentUserSafe();

        Soins s = new Soins();
        s.setUniqueId(java.util.UUID.randomUUID().toString());
        s.setProjet(projet);
        s.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        s.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        s.setType(data.getType());
        s.setProduit(data.getProduit());
        s.setQuantite(data.getQuantite());
        s.setCoutTotal(data.getCoutTotal());
        s.setObservations(data.getObservations());
        s.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            s.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            s.setFarm(currentUser.getFarm());
        }

        Soins saved = soinsRepo.save(s);
        syncTransaction(saved, currentUser);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Soins",
                    "Saisie de soins (" + saved.getType() + " — " + saved.getProduit() + ") pour le projet '" + projet.getTitre() + "'");
        }

        return SoinsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public SoinsDTO update(String uniqueId, SoinsUpdate data) {
        Soins s = soinsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Soins introuvable : " + uniqueId));

        if (data.getDate() != null) s.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) s.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getType() != null) s.setType(data.getType());
        if (data.getProduit() != null) s.setProduit(data.getProduit());
        if (data.getQuantite() != null) s.setQuantite(data.getQuantite());
        if (data.getCoutTotal() != null) s.setCoutTotal(data.getCoutTotal());
        if (data.getObservations() != null) s.setObservations(data.getObservations());
        if (data.getBatimentUniqueId() != null) {
            s.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (s.getInitialisation() != null) {
            s.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        Soins saved = soinsRepo.save(s);

        Utilisateurs currentUser = getCurrentUserSafe();
        syncTransaction(saved, currentUser);
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Soins", "Modification d'une saisie de soins");
        }

        return SoinsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Soins s = soinsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Soins introuvable : " + uniqueId));

        s.getInitialisation().setRemoved(!s.getInitialisation().getRemoved());
        soinsRepo.save(s);
        boolean removed = s.getInitialisation().getRemoved();
        transactionService.toggleRemovedBySource(s.getUniqueId());

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), s.getId(), "Soins",
                    (removed ? "Suppression" : "Restauration") + " d'une saisie de soins");
        }

        return removed ? "Saisie supprimée." : "Saisie récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<SoinsDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<Soins> resultPage = soinsRepo.search(farmId, projetParam, batimentParam, searchParam, pageable);

        List<SoinsDTO> dtoList = resultPage.getContent().stream()
                .map(SoinsDTO::fromEntity)
                .toList();

        return new PaginatedResponse<>(
                dtoList,
                resultPage.getNumber() + 1,
                resultPage.getTotalPages(),
                resultPage.getTotalElements(),
                resultPage.getSize()
        );
    }
}
