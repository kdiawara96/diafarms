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

import com.diafarms.ml.DTO.ConsommationAlimentDTO;
import com.diafarms.ml.DTO.StockAlimentDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.ConsommationAliment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.ConsommationAlimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.request.create.ConsommationAlimentCreate;
import com.diafarms.ml.request.update.ConsommationAlimentUpdate;
import com.diafarms.ml.services.ConsommationAlimentService;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ConsommationAlimentImpl implements ConsommationAlimentService {

    private final ConsommationAlimentRepo consommationRepo;
    private final AlimentationRepo alimentationRepo;
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
    public ConsommationAlimentDTO create(ConsommationAlimentCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        if (data.getQuantiteKg() == null || data.getQuantiteKg() <= 0) {
            throw new IllegalArgumentException("La quantité consommée doit être positive.");
        }

        double totalAchete = alimentationRepo.sumAcheteByProjetId(projet.getId());
        double totalConsomme = consommationRepo.sumConsommeByProjetId(projet.getId());
        double restant = totalAchete - totalConsomme;
        if (data.getQuantiteKg() > restant) {
            throw new IllegalArgumentException(
                "Stock d'aliment insuffisant pour ce projet (" + restant + " kg restants)."
            );
        }

        Utilisateurs currentUser = getCurrentUserSafe();

        ConsommationAliment c = new ConsommationAliment();
        c.setUniqueId(java.util.UUID.randomUUID().toString());
        c.setProjet(projet);
        c.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        c.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        c.setQuantiteKg(data.getQuantiteKg());
        c.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            c.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            c.setFarm(currentUser.getFarm());
        }

        ConsommationAliment saved = consommationRepo.save(c);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "ConsommationAliment",
                    "Saisie de consommation d'aliment (" + saved.getQuantiteKg() + " kg) pour le projet '" + projet.getTitre() + "'");
        }

        return ConsommationAlimentDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public ConsommationAlimentDTO update(String uniqueId, ConsommationAlimentUpdate data) {
        ConsommationAliment c = consommationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Consommation introuvable : " + uniqueId));

        if (data.getDate() != null) c.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) c.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getQuantiteKg() != null) {
            if (data.getQuantiteKg() <= 0) {
                throw new IllegalArgumentException("La quantité consommée doit être positive.");
            }
            Long projetId = c.getProjet().getId();
            double totalAchete = alimentationRepo.sumAcheteByProjetId(projetId);
            double totalConsomme = consommationRepo.sumConsommeByProjetId(projetId);
            double nouveauTotalConsomme = totalConsomme - c.getQuantiteKg() + data.getQuantiteKg();
            if (nouveauTotalConsomme > totalAchete) {
                throw new IllegalArgumentException(
                    "Stock d'aliment insuffisant pour ce projet (" + (totalAchete - (totalConsomme - c.getQuantiteKg())) + " kg restants)."
                );
            }
            c.setQuantiteKg(data.getQuantiteKg());
        }
        if (data.getBatimentUniqueId() != null) {
            c.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (c.getInitialisation() != null) {
            c.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        ConsommationAliment saved = consommationRepo.save(c);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "ConsommationAliment", "Modification d'une consommation d'aliment");
        }

        return ConsommationAlimentDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        ConsommationAliment c = consommationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Consommation introuvable : " + uniqueId));

        c.getInitialisation().setRemoved(!c.getInitialisation().getRemoved());
        consommationRepo.save(c);
        boolean removed = c.getInitialisation().getRemoved();

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), c.getId(), "ConsommationAliment",
                    (removed ? "Suppression" : "Restauration") + " d'une consommation d'aliment");
        }

        return removed ? "Consommation supprimée." : "Consommation récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<ConsommationAlimentDTO> list(int page, int size, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<ConsommationAliment> resultPage = consommationRepo.search(farmId, projetParam, batimentParam, pageable);

        List<ConsommationAlimentDTO> dtoList = resultPage.getContent().stream()
                .map(ConsommationAlimentDTO::fromEntity)
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
    public StockAlimentDTO getStock(String projetUniqueId) {
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));

        Double totalAchete = alimentationRepo.sumAcheteByProjetId(projet.getId());
        Double totalConsomme = consommationRepo.sumConsommeByProjetId(projet.getId());
        double restant = (totalAchete != null ? totalAchete : 0.0) - (totalConsomme != null ? totalConsomme : 0.0);

        return StockAlimentDTO.builder()
                .totalAchete(totalAchete != null ? totalAchete : 0.0)
                .totalConsomme(totalConsomme != null ? totalConsomme : 0.0)
                .stockRestant(restant)
                .statut(restant <= 0 ? "EPUISE" : "ACTIF")
                .build();
    }
}
