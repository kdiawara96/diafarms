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

import com.diafarms.ml.DTO.CollecteOeufsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.CollecteOeufs;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.CollecteOeufsRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.request.create.CollecteOeufsCreate;
import com.diafarms.ml.request.update.CollecteOeufsUpdate;
import com.diafarms.ml.services.CollecteOeufsService;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CollecteOeufsImpl implements CollecteOeufsService {

    private final CollecteOeufsRepo collecteOeufsRepo;
    private final ProjetsRepo projetsRepo;
    private final BatimentRepo batimentRepo;
    private final LogsServices logs;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    @Transactional
    public CollecteOeufsDTO create(CollecteOeufsCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        Utilisateurs currentUser = getCurrentUserSafe();

        CollecteOeufs c = new CollecteOeufs();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setProjet(projet);
        c.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        c.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        c.setOeufsCollectes(data.getOeufsCollectes() != null ? data.getOeufsCollectes() : 0);
        c.setOeufsCasses(data.getOeufsCasses() != null ? data.getOeufsCasses() : 0);
        c.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            c.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            c.setFarm(currentUser.getFarm());
        }

        CollecteOeufs saved = collecteOeufsRepo.save(c);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "CollecteOeufs",
                    "Saisie de collecte d'œufs (" + saved.getOeufsCollectes() + " œufs, " + saved.getOeufsCasses()
                            + " cassés) pour le projet '" + projet.getTitre() + "'");
        }

        return CollecteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public CollecteOeufsDTO update(String uniqueId, CollecteOeufsUpdate data) {
        CollecteOeufs c = collecteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Collecte introuvable : " + uniqueId));

        if (data.getDate() != null) c.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) c.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getOeufsCollectes() != null) c.setOeufsCollectes(data.getOeufsCollectes());
        if (data.getOeufsCasses() != null) c.setOeufsCasses(data.getOeufsCasses());
        if (data.getBatimentUniqueId() != null) {
            c.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (c.getInitialisation() != null) {
            c.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        CollecteOeufs saved = collecteOeufsRepo.save(c);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "CollecteOeufs", "Modification d'une collecte d'œufs");
        }

        return CollecteOeufsDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        CollecteOeufs c = collecteOeufsRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Collecte introuvable : " + uniqueId));

        c.getInitialisation().setRemoved(!c.getInitialisation().getRemoved());
        collecteOeufsRepo.save(c);
        boolean removed = c.getInitialisation().getRemoved();

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), c.getId(), "CollecteOeufs",
                    (removed ? "Suppression" : "Restauration") + " d'une collecte d'œufs");
        }

        return removed ? "Collecte supprimée." : "Collecte récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<CollecteOeufsDTO> list(int page, int size, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<CollecteOeufs> resultPage = collecteOeufsRepo.search(farmId, projetParam, batimentParam, pageable);

        List<CollecteOeufsDTO> dtoList = resultPage.getContent().stream()
                .map(CollecteOeufsDTO::fromEntity)
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
