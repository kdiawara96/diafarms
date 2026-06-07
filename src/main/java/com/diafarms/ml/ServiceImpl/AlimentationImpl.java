package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.AlimentationDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Alimentation;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AlimentationRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.request.create.AlimentationCreate;
import com.diafarms.ml.request.update.AlimentationUpdate;
import com.diafarms.ml.services.AlimentationService;
import com.diafarms.ml.services.LogsServices;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AlimentationImpl implements AlimentationService {
    

    private final AlimentationRepo alimentationRepo;
    private final ProjetsRepo projetsRepo;
    private final OtherService otherService;
    private final LogsServices logs;

    // --- Génération UID ---   
    private String generateUID() {
        return "ALI-" + java.util.UUID.randomUUID().toString();
    }

    // --- Récupération utilisateur safe ---
    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            System.err.println("Impossible de récupérer l'utilisateur connecté : " + e.getMessage());
            return null;
        }
    }

    // --- Log helper ---
    private void logAction(Utilisateurs currentUser, Alimentation alimentation, String message) {
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), alimentation.getId(), "Alimentation", message);
        }
    }

    // ============================================================
    // SAVE
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO save(AlimentationCreate data, String uniqueIdProjet) {
        // 1. Vérifier le projet
        Projets projet = projetsRepo.findByUniqueId(uniqueIdProjet)
                .orElseThrow(() -> new RuntimeException("Projet non trouvé avec l'UID : " + uniqueIdProjet));

        // 2. Récupérer l'utilisateur et sa ferme
        Utilisateurs currentUser = getCurrentUserSafe();
        Farm farm = currentUser != null ? currentUser.getFarm() : null;

        // 3. Créer l'entité
        Alimentation alimentation = new Alimentation();
        alimentation.setUniqueId(generateUID());
        alimentation.setNomAliment(data.getNomAliment());
        alimentation.setSac(data.getSac());
        alimentation.setQuantiteKg(data.getQuantiteKg());
        alimentation.setCoutTotal(data.getCoutTotal());
        alimentation.setDateDistribution(
            data.getDateDistribution() != null
                ? LocalDate.parse(data.getDateDistribution())
                : LocalDate.now()
        );
        alimentation.setObservations(data.getObservations());
        alimentation.setProjet(projet);
        alimentation.setFarm(farm);
        alimentation.setInitialisation(Initialisation.init());

        // 4. Sauvegarder
        Alimentation saved = alimentationRepo.save(alimentation);

        // 5. Log
        logAction(currentUser, saved,
            "Création de l'alimentation '" + saved.getNomAliment()
                + "' (" + saved.getQuantiteKg() + " kg, " + saved.getSac() + " sacs) pour le projet '"
                + projet.getTitre() + "' | Coût total : " + saved.getCoutTotal() + " FCFA"
        );

        return AlimentationDTO.fromEntityList(saved);
    }

    // ============================================================
    // UPDATE
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO update(String uniqueId, AlimentationUpdate data) {
        // 1. Trouver l'alimentation
        Alimentation alimentation = alimentationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Alimentation non trouvée avec l'UID : " + uniqueId));

        // 2. Vérifier si non supprimée
        if (Boolean.TRUE.equals(alimentation.getInitialisation().getRemoved())) {
            throw new RuntimeException("Cette alimentation a été supprimée et ne peut pas être modifiée.");
        }

        // 3. Sauvegarder anciennes valeurs pour le log
        String ancienNom = alimentation.getNomAliment();
        Double ancienneQuantite = alimentation.getQuantiteKg();
        Double ancienCout = alimentation.getCoutTotal();

        // 4. Mettre à jour les champs
        if (data.getNomAliment() != null && !data.getNomAliment().trim().isEmpty()) {
            alimentation.setNomAliment(data.getNomAliment());
        }
        if (data.getSac() != null) {
            alimentation.setSac(data.getSac());
        }
        if (data.getQuantiteKg() != null) {
            alimentation.setQuantiteKg(data.getQuantiteKg());
        }
        if (data.getCoutTotal() != null) {
            alimentation.setCoutTotal(data.getCoutTotal());
        }
        if (data.getDateDistribution() != null) {
            alimentation.setDateDistribution(
                LocalDate.parse(data.getDateDistribution())
            );
        }
        if (data.getObservations() != null) {
            alimentation.setObservations(data.getObservations());
        }

        // Mise à jour date
        alimentation.setInitialisation(Initialisation.updateDate(alimentation.getInitialisation()));

        // 5. Sauvegarder
        Alimentation updated = alimentationRepo.save(alimentation);

        // 6. Log
        Utilisateurs currentUser = getCurrentUserSafe();
        logAction(currentUser, updated,
            "Modification de l'alimentation '" + ancienNom + "' → '" + updated.getNomAliment()
                + "' | Quantité : " + ancienneQuantite + " → " + updated.getQuantiteKg()
                + " kg | Coût : " + ancienCout + " → " + updated.getCoutTotal() + " FCFA"
        );

        return AlimentationDTO.fromEntityList(updated);
    }

    // ============================================================
    // DELETE (Soft Delete)
    // ============================================================
    @Override
    @Transactional
    public AlimentationDTO delete(String uniqueId) {
        // 1. Trouver l'alimentation
        Alimentation alimentation = alimentationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Alimentation non trouvée avec l'UID : " + uniqueId));

        // 2. Vérifier si déjà supprimée
        if (Boolean.TRUE.equals(alimentation.getInitialisation().getRemoved())) {
            throw new RuntimeException("Cette alimentation est déjà supprimée.");
        }

        // 3. Soft delete
        alimentation.getInitialisation().setRemoved(true);
        alimentation.setInitialisation(Initialisation.updateDate(alimentation.getInitialisation()));

        Alimentation deleted = alimentationRepo.save(alimentation);

        // 4. Log
        Utilisateurs currentUser = getCurrentUserSafe();
        logAction(currentUser, deleted,
            "Suppression de l'alimentation '" + deleted.getNomAliment()
                + "' (" + deleted.getQuantiteKg() + " kg) du projet '"
                + deleted.getProjet().getTitre() + "'"
        );

        return AlimentationDTO.fromEntityList(deleted);
    }

    // ============================================================
    // LIST BY PROJECT
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public List<AlimentationDTO> findByProjetUniqueId(String uniqueIdProjet) {
        return alimentationRepo.findByProjetUniqueIdAndInitialisationRemovedFalse(uniqueIdProjet)
                .stream()
                .map(AlimentationDTO::fromEntityList)
                .toList();
    }


    // ============================================================
    // GET BY UNIQUE ID
    // ============================================================
    @Override
    @Transactional(readOnly = true)
    public AlimentationDTO findByUniqueId(String uniqueId) {
        Alimentation alimentation = alimentationRepo.findByUniqueIdAndInitialisationRemovedFalse(uniqueId)
                .orElseThrow(() -> new RuntimeException("Alimentation non trouvée avec l'UID : " + uniqueId));
        return AlimentationDTO.fromEntityList(alimentation);
    }


}
