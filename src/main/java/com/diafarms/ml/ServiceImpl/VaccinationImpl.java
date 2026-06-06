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
    public VaccinationDTO update(String uniqueId, VaccinationDTO data) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'update'");
    }
    @Override
    public VaccinationDTO delete(String uniqueId) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'delete'");
    }

}
