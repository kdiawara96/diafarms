package com.diafarms.ml.ServiceImpl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.BatimentsDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.services.BatimentServices;
import com.diafarms.ml.services.LogsServices;

import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class BatimentImpl implements BatimentServices {

    private final BatimentRepo batimentRepo;
    private final LogsServices logs;
    private final OtherService OtherService;
    
    @Override
    public BatimentsDTO create(Batiment batiment) {

        batiment.setUniqueId(UUID.randomUUID().toString());
        batiment.setInitialisation(Initialisation.init());

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (currentUser != null) {

            if (batimentRepo.existsByNomIgnoreCaseAndFarmId(
                    batiment.getNom().trim(),
                    currentUser.getFarm().getId())) {

                throw new RuntimeException(
                        "Un bâtiment portant ce nom existe déjà."
                );
            }

            batiment.setFarm(currentUser.getFarm());
        }

        Batiment savedBatiment = batimentRepo.save(batiment);

        if (currentUser != null) {
            logs.addLogs(
                    currentUser.getId(),
                    savedBatiment.getId(),
                    "Batiment",
                    "Ajout d'un bâtiment avec succès !"
            );
        }

        return BatimentsDTO.toDTO(savedBatiment);
    }

    @Transactional
    @Override
    public BatimentsDTO update(Batiment batiment) {
        
        // 1. Récupérer le bâtiment existant
        Batiment existingBatiment = batimentRepo.findByUniqueId(batiment.getUniqueId());
        if (existingBatiment == null) {
            throw new RuntimeException("Bâtiment non trouvé !");
        }

        // 2. Vérification du nom (uniquement si le nom a changé)
        String nouveauNom = batiment.getNom();
        if (nouveauNom != null && !nouveauNom.trim().isEmpty()) {
            
            if (!existingBatiment.getNom().equalsIgnoreCase(nouveauNom)) {
                
                Optional<Batiment> batimentExistant = batimentRepo.findOptionalByNom(nouveauNom.trim());
                
                if (batimentExistant.isPresent()) {
                    Batiment autreBatiment = batimentExistant.get();
                    
                    // On exclut bien l'entité en cours de modification
                    if (!autreBatiment.getUniqueId().equals(batiment.getUniqueId())) {
                        throw new RuntimeException("Un bâtiment avec ce nom existe déjà !");
                    }
                }
            }
        } else {
            throw new RuntimeException("Le nom du bâtiment est obligatoire !");
        }

        // 3. Mise à jour des champs
        existingBatiment.setNom(nouveauNom.trim());
        existingBatiment.setCapacite(batiment.getCapacite());
        existingBatiment.setType(batiment.getType());
        existingBatiment.setStatut(batiment.getStatut());
        existingBatiment.setDescription(batiment.getDescription());
        existingBatiment.setDateDerniereMaintenance(batiment.getDateDerniereMaintenance());
        existingBatiment.setSuperficieM2(batiment.getSuperficieM2());
        
        existingBatiment.setInitialisation(Initialisation.updateDate(existingBatiment.getInitialisation()));

        // 4. Logs
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), existingBatiment.getId(), "Batiment", "Mise à jour d'un bâtiment");
        }

        return BatimentsDTO.toDTO(batimentRepo.save(existingBatiment));
    }


    @Override
    public String deleteOrRecover(String uniqueIdBatiment) {
        
        Batiment batiment = batimentRepo.findByUniqueId(uniqueIdBatiment);
        if (batiment == null) {
            throw new RuntimeException("Bâtiment non trouvé !");
        }

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (batiment.getInitialisation().getRemoved()) {
            // Récupération (Restore)
            batiment.getInitialisation().setRemoved(false);
            batimentRepo.save(batiment);
            
            if (currentUser != null) {
                logs.addLogs(currentUser.getId(), batiment.getId(), "Batiment", 
                            "Récupération d'un bâtiment");
            }
            return "Bâtiment récupéré avec succès !";
            
        } else {
            // Suppression logique (Soft Delete)
            batiment.getInitialisation().setRemoved(true);
            batimentRepo.save(batiment);
            
            if (currentUser != null) {
                logs.addLogs(currentUser.getId(), batiment.getId(), "Batiment", 
                            "Suppression d'un bâtiment");
            }
            return "Bâtiment supprimé avec succès !";
        }
    }

    @Override
    public List<BatimentsDTO> findAll() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        List<Batiment> batiments = List.of();

        if (currentUser != null) {
            batiments = batimentRepo.findActiveByFarmId(currentUser.getFarm().getId()); // À créer dans le repo
        }
        return batiments.stream()
                        .map(BatimentsDTO::toDTO)
                        .collect(Collectors.toList());
    }

   @Override
    public List<BatimentsDTO> search(String search) {
        if (search == null || search.trim().isEmpty()) {
            return findAll();
        }

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }


        List<Batiment> batiments = List.of();

        if (currentUser != null) {
            batiments = batimentRepo.searchBatimentsByFarm(currentUser.getFarm().getId(), search.trim());
        }
        return batiments.stream()
                        .map(BatimentsDTO::toDTO)
                        .collect(Collectors.toList());
    }

   @Override
   public List<BatimentsDTO> select() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        List<Batiment> batiments = List.of();

        if (currentUser != null) {
            batiments = batimentRepo.findAvailableByFarmId(currentUser.getFarm().getId());
        }
        return batiments.stream()
                        .map(BatimentsDTO::select)
                        .collect(Collectors.toList());
   }

}
