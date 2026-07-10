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

import com.diafarms.ml.DTO.MortaliteDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Mortalite;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.request.create.MortaliteCreate;
import com.diafarms.ml.request.update.MortaliteUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.MortaliteService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class MortaliteImpl implements MortaliteService {

    private final MortaliteRepo mortaliteRepo;
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
    public MortaliteDTO create(MortaliteCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        Utilisateurs currentUser = getCurrentUserSafe();

        Mortalite m = new Mortalite();
        m.setUniqueId(java.util.UUID.randomUUID().toString());
        m.setProjet(projet);
        m.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        m.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        m.setNombreMorts(data.getNombreMorts() != null ? data.getNombreMorts() : 0);
        m.setCause(data.getCause());
        m.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            m.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            m.setFarm(currentUser.getFarm());
        }

        Mortalite saved = mortaliteRepo.save(m);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Mortalite",
                    "Saisie de mortalité (" + saved.getNombreMorts() + " sujets) pour le projet '" + projet.getTitre() + "'"
                            + (saved.getCause() != null ? " — cause : " + saved.getCause() : ""));
        }

        return MortaliteDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public MortaliteDTO update(String uniqueId, MortaliteUpdate data) {
        Mortalite m = mortaliteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Mortalité introuvable : " + uniqueId));

        if (data.getDate() != null) m.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) m.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getNombreMorts() != null) m.setNombreMorts(data.getNombreMorts());
        if (data.getCause() != null) m.setCause(data.getCause());
        if (data.getBatimentUniqueId() != null) {
            m.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (m.getInitialisation() != null) {
            m.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        Mortalite saved = mortaliteRepo.save(m);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Mortalite", "Modification d'une saisie de mortalité");
        }

        return MortaliteDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Mortalite m = mortaliteRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Mortalité introuvable : " + uniqueId));

        m.getInitialisation().setRemoved(!m.getInitialisation().getRemoved());
        mortaliteRepo.save(m);
        boolean removed = m.getInitialisation().getRemoved();

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), m.getId(), "Mortalite",
                    (removed ? "Suppression" : "Restauration") + " d'une saisie de mortalité");
        }

        return removed ? "Saisie supprimée." : "Saisie récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<MortaliteDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<Mortalite> resultPage = mortaliteRepo.search(farmId, projetParam, batimentParam, searchParam, pageable);

        List<MortaliteDTO> dtoList = resultPage.getContent().stream()
                .map(MortaliteDTO::fromEntity)
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
