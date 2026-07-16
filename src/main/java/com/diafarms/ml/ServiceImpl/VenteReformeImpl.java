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
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.VenteReforme;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.MortaliteRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.VenteReformeRepo;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.TransactionService;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VenteReformeImpl implements VenteReformeService {

    private final VenteReformeRepo venteReformeRepo;
    private final MortaliteRepo mortaliteRepo;
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

    private int effectifVivant(Projets projet) {
        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(venteReformeRepo.sumSujetsVendusByProjetId(projet.getId()));
        return nbSujets - morts - dejaReformes;
    }

    private int nz(Integer v) {
        return v == null ? 0 : v;
    }

    @Override
    @Transactional
    public VenteReformeDTO create(VenteReformeCreate data) {
        Projets projet = projetsRepo.findByUniqueId(data.getProjetUniqueId())
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + data.getProjetUniqueId()));

        if (data.getNombreSujets() == null || data.getNombreSujets() <= 0) {
            throw new IllegalArgumentException("Le nombre de sujets vendus doit être positif.");
        }
        if (data.getMontant() == null || data.getMontant() <= 0) {
            throw new IllegalArgumentException("Le montant de la vente doit être positif.");
        }

        int restant = effectifVivant(projet);
        if (data.getNombreSujets() > restant) {
            throw new IllegalArgumentException(
                "Effectif vivant insuffisant pour ce projet (" + restant + " sujet(s) restants)."
            );
        }

        Utilisateurs currentUser = getCurrentUserSafe();

        VenteReforme v = new VenteReforme();
        v.setUniqueId(java.util.UUID.randomUUID().toString());
        v.setProjet(projet);
        v.setDate(data.getDate() != null ? LocalDate.parse(data.getDate()) : LocalDate.now());
        v.setHeure(data.getHeure() != null && !data.getHeure().isBlank() ? LocalTime.parse(data.getHeure()) : null);
        v.setNombreSujets(data.getNombreSujets());
        v.setPrixUnitaire(data.getPrixUnitaire());
        v.setMontant(data.getMontant());
        v.setInitialisation(Initialisation.init());

        if (data.getBatimentUniqueId() != null && !data.getBatimentUniqueId().isBlank()) {
            v.setBatiment(batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
        }
        if (currentUser != null) {
            v.setFarm(currentUser.getFarm());
        }

        VenteReforme saved = venteReformeRepo.save(v);

        transactionService.createFromSource(
                projet, saved.getMontant(), "Vente réforme", saved.getDate(),
                "Vente réforme — " + saved.getNombreSujets() + " sujet(s)",
                SourceTransaction.VENTE_REFORME, saved.getUniqueId()
        );

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "VenteReforme",
                    "Vente réforme de " + saved.getNombreSujets() + " sujet(s) (" + saved.getMontant() + " FCFA) pour le projet '" + projet.getTitre() + "'");
        }

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
            Projets projet = v.getProjet();
            int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
            int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
            int totalReforme = nz(venteReformeRepo.sumSujetsVendusByProjetId(projet.getId()));
            int nouveauTotalReforme = totalReforme - v.getNombreSujets() + data.getNombreSujets();
            if (nouveauTotalReforme > (nbSujets - morts)) {
                throw new IllegalArgumentException(
                    "Effectif vivant insuffisant pour ce projet (" + ((nbSujets - morts) - (totalReforme - v.getNombreSujets())) + " sujet(s) restants)."
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
        if (data.getBatimentUniqueId() != null) {
            v.setBatiment(data.getBatimentUniqueId().isBlank() ? null : batimentRepo.findByUniqueId(data.getBatimentUniqueId()));
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
    public PaginatedResponse<VenteReformeDTO> list(int page, int size, String projetUniqueId, String batimentUniqueId) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "date"));

        Utilisateurs currentUser = getCurrentUserSafe();
        Long farmId = currentUser != null && currentUser.getFarm() != null ? currentUser.getFarm().getId() : null;

        String projetParam = (projetUniqueId == null || projetUniqueId.isBlank()) ? null : projetUniqueId;
        String batimentParam = (batimentUniqueId == null || batimentUniqueId.isBlank()) ? null : batimentUniqueId;

        Page<VenteReforme> resultPage = venteReformeRepo.search(farmId, projetParam, batimentParam, pageable);

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
    public EffectifReformeDTO getEffectif(String projetUniqueId) {
        Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));

        int nbSujets = projet.getNbSujets() == null ? 0 : projet.getNbSujets();
        int morts = nz(mortaliteRepo.sumMortsByProjetId(projet.getId()));
        int dejaReformes = nz(venteReformeRepo.sumSujetsVendusByProjetId(projet.getId()));

        return EffectifReformeDTO.builder()
                .nbSujetsInitial(nbSujets)
                .mortaliteCumulee(morts)
                .sujetsReformesCumulee(dejaReformes)
                .effectifVivant(nbSujets - morts - dejaReformes)
                .build();
    }
}
