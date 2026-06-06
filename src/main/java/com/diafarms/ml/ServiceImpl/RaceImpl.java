package com.diafarms.ml.ServiceImpl;

import com.diafarms.ml.DTO.RaceDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Race;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.RaceRepo;
import com.diafarms.ml.services.RaceServices;
import com.diafarms.ml.services.LogsServices;
import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RaceImpl implements RaceServices {
    
    private final RaceRepo raceRepo;
    private final LogsServices logs;
    private final OtherService OtherService;

    @Override
    public RaceDTO create(Race race) {
        race.setUniqueId(java.util.UUID.randomUUID().toString());
        race.setInitialisation(Initialisation.init());

        // Génération de l'identifiant unique au format "RAC-001"
        race.setIdentifiant(genererIdentifiantUnique());

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (currentUser != null) {
            race.setFarm(currentUser.getFarm());
        }

        Race savedRace = raceRepo.save(race);
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), savedRace.getId(), "Race", "Ajout d'une race avec succès !");
        }
        return RaceDTO.toDTO(savedRace);
    }

    private String genererIdentifiantUnique() {
        long count = raceRepo.count() + 1;
        String identifiant;
        
        do {
            identifiant = String.format("RAC-%03d", count);
            count++;
        } while (raceRepo.existsByIdentifiant(identifiant)); // Requête SQL directe
        
        return identifiant;
    }
   
    @Transactional
    @Override
    public RaceDTO update(String uniqueId, Race race) {
        Race existingRace = raceRepo.findByUniqueId(uniqueId);
        if (existingRace == null) {
            throw new RuntimeException("Race non trouvée !");
        }
        String nouveauNom = race.getNom();
        if (nouveauNom != null && !nouveauNom.trim().isEmpty()) {
            if (!existingRace.getNom().equalsIgnoreCase(nouveauNom)) {
                Optional<Race> raceExistant = raceRepo.findOptionalByNom(nouveauNom.trim());
                if (raceExistant.isPresent()) {
                    Race autreRace = raceExistant.get();
                    if (!autreRace.getUniqueId().equals(uniqueId)) {
                        throw new RuntimeException("Une race avec ce nom existe déjà !");
                    }
                }
            }
        } else {
            throw new RuntimeException("Le nom de la race est obligatoire !");
        }

        existingRace.setNom(nouveauNom.trim());
        existingRace.setIdentifiant(existingRace.getIdentifiant()); // L'identifiant ne change pas
        existingRace.setType(race.getType());
        existingRace.setOrigine(race.getOrigine());
        existingRace.setDescription(race.getDescription());
        existingRace.setEsperanceVieAnnees(race.getEsperanceVieAnnees());
        existingRace.setPoidsAdulteKg(race.getPoidsAdulteKg());
        existingRace.setProductionOeufsAn(race.getProductionOeufsAn());
        existingRace.setCouleurOeuf(race.getCouleurOeuf());
        existingRace.setTempsCroissance(race.getTempsCroissance());
        existingRace.setPoidsAbattage(race.getPoidsAbattage());
        existingRace.setRusticite(race.getRusticite());
        existingRace.setAdaptationClimat(race.getAdaptationClimat());
        existingRace.setCertificationRace(race.getCertificationRace());
        existingRace.setInitialisation(Initialisation.updateDate(existingRace.getInitialisation()));

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), existingRace.getId(), "Race", "Mise à jour d'une race");
        }
        return RaceDTO.toDTO(raceRepo.save(existingRace));
    }

    @Override
    public String deleteOrRecover(String uniqueIdRace) {
        Race race = raceRepo.findByUniqueId(uniqueIdRace);
        if (race == null) {
            throw new RuntimeException("Race non trouvée !");
        }
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (race.getInitialisation().getRemoved()) {
            race.getInitialisation().setRemoved(false);
            raceRepo.save(race);
            if (currentUser != null) {
                logs.addLogs(currentUser.getId(), race.getId(), "Race", "Récupération d'une race");
            }
            return "Race récupérée avec succès !";
        } else {
            race.getInitialisation().setRemoved(true);
            raceRepo.save(race);
            if (currentUser != null) {
                logs.addLogs(currentUser.getId(), race.getId(), "Race", "Suppression d'une race");
            }
            return "Race supprimée avec succès !";
        }
    }

    @Override
    public List<RaceDTO> findAll() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Si un utilisateur est connecté, on filtre par sa ferme
        if (currentUser != null && currentUser.getFarm() != null) {
            return raceRepo.findAllActiveByFarm(currentUser.getFarm().getId()).stream()
                    .map(RaceDTO::toDTO)
                    .collect(Collectors.toList());
        }

        // Cas de secours (ex: pas d'utilisateur connecté ou pas de ferme associée)
        // Vous pouvez soit lever une exception, soit retourner une liste vide, soit tout afficher
        return Collections.emptyList(); 
    }

    @Override
    public List<RaceDTO> search(String search) {
        if (search == null || search.trim().isEmpty()) {
            return findAll();
        }

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Si un utilisateur est connecté, on recherche uniquement dans sa ferme
        if (currentUser != null && currentUser.getFarm() != null) {
            return raceRepo.searchRacesByFarm(currentUser.getFarm().getId(), search.trim()).stream()
                    .map(RaceDTO::toDTO)
                    .collect(Collectors.toList());
        }

        // Cas de secours si aucun utilisateur/ferme n'est trouvé
        return Collections.emptyList();
    }

    @Override
    public List<RaceDTO> select() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        // Si un utilisateur est connecté, on filtre par sa ferme
        if (currentUser != null && currentUser.getFarm() != null) {
            return raceRepo.findAllActiveByFarm(currentUser.getFarm().getId()).stream()
                    .map(RaceDTO::toDTO)
                    .collect(Collectors.toList());
        }

        // Cas de secours (ex: pas d'utilisateur connecté ou pas de ferme associée)
        // Vous pouvez soit lever une exception, soit retourner une liste vide, soit tout afficher
        return Collections.emptyList();
    }

}
