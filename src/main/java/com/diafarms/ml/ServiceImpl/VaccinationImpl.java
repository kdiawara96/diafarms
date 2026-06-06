package com.diafarms.ml.ServiceImpl;

import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.DTO.VaccinationDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.models.Vaccination;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.repository.VaccinationRepo;
import com.diafarms.ml.request.create.VaccinCreate;
import com.diafarms.ml.request.update.VaccinUpdate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.VaccinationService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class VaccinationImpl implements VaccinationService {

   
    private final VaccinationRepo vaccinationRepo;
    private final OtherService OtherService;
    private final ProjetsRepo projetsRepo;
    private final LogsServices logs;


     private String generateUID() {
        return "VAC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ============================================================
    // SAVE
    // ============================================================
    @Override
    @Transactional
    public VaccinationDTO save(VaccinCreate data, String uniqueIdProjet) {
        
        // 1. Vérifier le projet
        Projets projet = projetsRepo.findByUniqueId(uniqueIdProjet)
                .orElseThrow(() -> new RuntimeException("Projet non trouvé avec l'UID : " + uniqueIdProjet));

        // 2. Récupérer l'utilisateur connecté et sa ferme
        Utilisateurs currentUser = null;
        Farm farm = null;
        
        try {
            currentUser = OtherService.getCurrentUser();
            farm = currentUser != null ? currentUser.getFarm() : null;
        } catch (Exception e) {
            System.err.println("Impossible de récupérer l'utilisateur connecté : " + e.getMessage());
        }

        // 3. Calculer le coût total (c'est un achat, pas un CA)
        double coutTotal = data.getQuantite() * data.getPrixUnitaire();

        // 4. Créer l'entité
        Vaccination vaccination = new Vaccination();
        vaccination.setUniqueId(generateUID());
        vaccination.setNomVaccin(data.getNomVaccin());
        vaccination.setQuantite(data.getQuantite());
        vaccination.setPrixUnitaire(data.getPrixUnitaire());
        
        // Mode administration (safe)
        if (data.getModeAdministration() != null && !data.getModeAdministration().isEmpty()) {
            vaccination.setModeAdministration(
                data.getModeAdministration().stream()
                    .filter(m -> m != null && !m.trim().isEmpty())
                    .collect(Collectors.joining(" | "))
            );
        }
        
        vaccination.setCoutTotal(coutTotal);  // ✅ On set manuellement
        vaccination.setProjet(projet);
        vaccination.setFarm(farm);
        vaccination.setInitialisation(Initialisation.init());

        // 5. Sauvegarder
        Vaccination saved = vaccinationRepo.save(vaccination);

        // 6. Log
        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                saved.getId(),
                "Vaccination",
                "Création du vaccin '" + saved.getNomVaccin() 
                    + "' (" + saved.getQuantite() + " doses) pour le projet '" + projet.getTitre() 
                    + "' | Coût total : " + saved.getCoutTotal() + " FCFA"
            );
        }

        return VaccinationDTO.fromEntity(saved);
    }

    @Override
    @Transactional
    public VaccinationDTO update(String uniqueId, VaccinUpdate data) {
        // 1. Trouver la vaccination existante
        Vaccination vaccination = vaccinationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Vaccination non trouvée avec l'UID : " + uniqueId));

        // 2. Vérifier si non supprimée
        if (Boolean.TRUE.equals(vaccination.getInitialisation().getRemoved())) {
            throw new RuntimeException("Cette vaccination a été supprimée et ne peut pas être modifiée.");
        }

        // 3. Sauvegarder les anciennes valeurs pour le log
        String ancienNom = vaccination.getNomVaccin();
        Integer ancienneQuantite = vaccination.getQuantite();
        Double ancienCout = vaccination.getCoutTotal();

        // 4. Mettre à jour les champs
        if (data.getNomVaccin() != null && !data.getNomVaccin().trim().isEmpty()) {
            vaccination.setNomVaccin(data.getNomVaccin());
        }
        if (data.getQuantite() != null) {
            vaccination.setQuantite(data.getQuantite());
        }
        if (data.getPrixUnitaire() != null) {
            vaccination.setPrixUnitaire(data.getPrixUnitaire());
        }
        if (data.getModeAdministration() != null && !data.getModeAdministration().isEmpty()) {
            vaccination.setModeAdministration(
                data.getModeAdministration().stream()
                    .filter(m -> m != null && !m.trim().isEmpty())
                    .collect(Collectors.joining(" | "))
            );
        }

        // Recalcul du coût total
        vaccination.setCoutTotal(vaccination.getQuantite() * vaccination.getPrixUnitaire());
        
        // Mise à jour de la date
        vaccination.setInitialisation(Initialisation.updateDate(vaccination.getInitialisation()));

        // 5. Sauvegarder
        Vaccination updated = vaccinationRepo.save(vaccination);

        // 6. Log
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            System.err.println("Impossible de récupérer l'utilisateur connecté : " + e.getMessage());
        }
        
        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                updated.getId(),
                "Vaccination",
                "Modification du vaccin '" + ancienNom 
                    + "' → '" + updated.getNomVaccin() 
                    + "' | Quantité : " + ancienneQuantite + " → " + updated.getQuantite()
                    + " | Coût : " + ancienCout + " → " + updated.getCoutTotal() + " FCFA"
            );
        }

        return VaccinationDTO.fromEntity(updated);
    }

    @Override
    @Transactional
    public VaccinationDTO delete(String uniqueId) {
        // 1. Trouver la vaccination
        Vaccination vaccination = vaccinationRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Vaccination non trouvée avec l'UID : " + uniqueId));

        // 2. Vérifier si déjà supprimée
        if (Boolean.TRUE.equals(vaccination.getInitialisation().getRemoved())) {
            throw new RuntimeException("Cette vaccination est déjà supprimée.");
        }

        // 3. Soft delete
        vaccination.getInitialisation().setRemoved(true);
        vaccination.setInitialisation(Initialisation.updateDate(vaccination.getInitialisation()));

        Vaccination deleted = vaccinationRepo.save(vaccination);

        // 4. Log
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            System.err.println("Impossible de récupérer l'utilisateur connecté : " + e.getMessage());
        }
        
        if (currentUser != null) {
            logs.addLogs(
                currentUser.getId(),
                deleted.getId(),
                "Vaccination",
                "Suppression du vaccin '" + deleted.getNomVaccin() 
                    + "' (" + deleted.getQuantite() + " doses) du projet '" 
                    + deleted.getProjet().getTitre() + "'"
            );
        }

        return VaccinationDTO.fromEntity(deleted);
    }

}
