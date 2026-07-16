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

import com.diafarms.ml.DTO.EffectifReformeDTO;
import com.diafarms.ml.DTO.ReformeDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Reforme;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.ReformeRepo;
import com.diafarms.ml.request.create.ReformeCreate;
import com.diafarms.ml.request.update.ReformeUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.ReformeService;

import lombok.RequiredArgsConstructor;

// Réforme (Production) : comptage pur des sujets retirés du cheptel vivant, plafonné
// par l'effectif vivant du projet (nbSujets - mortalité - déjà réformé) — même
// principe que le plafond de ConsommationAlimentImpl, appliqué ici à un cheptel
// plutôt qu'à un stock d'aliment. Ne contient aucun prix : la vente (avec montant)
// est un acte Finance distinct et séparé (VenteReformeImpl), à l'échelle de la ferme.
@Service
@RequiredArgsConstructor
public class ReformeImpl implements ReformeService {

    private final ReformeRepo reformeRepo;
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

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private int effectifVivant(Projets projet) {
        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
        return nbSujets - morts - dejaReformes;
    }

    @Override
    @Transactional
    public ReformeDTO create(ReformeCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        if (data.getNombreSujets() == null || data.getNombreSujets() <= 0) {
            throw new IllegalArgumentException("Le nombre de sujets réformés doit être positif.");
        }

        int restant = effectifVivant(projet);
        if (data.getNombreSujets() > restant) {
            throw new IllegalArgumentException(
                "Effectif vivant insuffisant pour ce projet (" + restant + " sujet(s) restants)."
            );
        }

        Utilisateurs currentUser = getCurrentUserSafe();

        Reforme r = new Reforme();
        r.setUniqueId(java.util.UUID.randomUUID().toString());
        r.setProjet(projet);
        r.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        r.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        r.setNombreSujets(data.getNombreSujets());
        r.setCause(data.getCause());
        r.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            r.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            r.setFarm(currentUser.getFarm());
        }

        Reforme saved = reformeRepo.save(r);

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Reforme",
                    "Réforme de " + saved.getNombreSujets() + " sujet(s) pour le projet '" + projet.getTitre() + "'"
                            + (saved.getCause() != null ? " — cause : " + saved.getCause() : ""));
        }

        return ReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public ReformeDTO update(String uniqueId, ReformeUpdate data) {
        Reforme r = reformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Réforme introuvable : " + uniqueId));

        if (data.getDate() != null) r.setDate(LocalDate.parse(data.getDate()));
        if (data.getHeure() != null) r.setHeure(data.getHeure().isBlank() ? null : LocalTime.parse(data.getHeure()));
        if (data.getNombreSujets() != null) {
            if (data.getNombreSujets() <= 0) {
                throw new IllegalArgumentException("Le nombre de sujets réformés doit être positif.");
            }
            Projets projet = r.getProjet();
            int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
            int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
            int totalReforme = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));
            int nouveauTotalReforme = totalReforme - r.getNombreSujets() + data.getNombreSujets();
            if (nouveauTotalReforme > (nbSujets - morts)) {
                throw new IllegalArgumentException(
                    "Effectif vivant insuffisant pour ce projet (" + ((nbSujets - morts) - (totalReforme - r.getNombreSujets())) + " sujet(s) restants)."
                );
            }
            r.setNombreSujets(data.getNombreSujets());
        }
        if (data.getCause() != null) r.setCause(data.getCause());
        if (data.getBatimentUniqueId() != null) {
            r.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (r.getInitialisation() != null) {
            r.getInitialisation().setUpdatedAt(java.time.LocalDateTime.now());
        }

        Reforme saved = reformeRepo.save(r);

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Reforme", "Modification d'une saisie de réforme");
        }

        return ReformeDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public String deleteOrRecover(String uniqueId) {
        Reforme r = reformeRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Réforme introuvable : " + uniqueId));

        r.getInitialisation().setRemoved(!r.getInitialisation().getRemoved());
        reformeRepo.save(r);
        boolean removed = r.getInitialisation().getRemoved();

        Utilisateurs currentUser = getCurrentUserSafe();
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), r.getId(), "Reforme",
                    (removed ? "Suppression" : "Restauration") + " d'une saisie de réforme");
        }

        return removed ? "Saisie supprimée." : "Saisie récupérée.";
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<ReformeDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String searchParam = (search == null || search.isBlank()) ? null : "%" + search.trim().toLowerCase() + "%";
        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<Reforme> resultPage = reformeRepo.search(farmId, projetParam, batimentParam, searchParam, pageable);

        List<ReformeDTO> dtoList = resultPage.getContent().stream()
                .map(ReformeDTO::fromEntity)
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
    public EffectifReformeDTO getEffectif(String projetUniqueId) {
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));

        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(reformeRepo.sumSujetsByProjetId(projet.getId()));

        return EffectifReformeDTO.builder()
                .nbSujetsInitial(nbSujets)
                .mortaliteCumulee(morts)
                .sujetsReformesCumulee(dejaReformes)
                .effectifVivant(nbSujets - morts - dejaReformes)
                .build();
    }
}
