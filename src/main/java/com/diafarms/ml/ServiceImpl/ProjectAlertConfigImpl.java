package com.diafarms.ml.ServiceImpl;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.AlertCountDTO;
import com.diafarms.ml.DTO.ProjectAlertTableDTO;
import com.diafarms.ml.DTO.UpdateAlertRequestDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.AlertLevel;
import com.diafarms.ml.enums.AlertStatus;
import com.diafarms.ml.enums.AlertType;
import com.diafarms.ml.models.ProjectAlertConfig;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.repository.ProjectAlertConfigRepo;
import com.diafarms.ml.repository.ProjetsRepo;
import com.diafarms.ml.services.ProjectAlertConfigService;
import com.diafarms.ml.template.ProjectAlertTemplate;

import org.springframework.transaction.annotation.Transactional;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class ProjectAlertConfigImpl implements ProjectAlertConfigService{

    private final ProjetsRepo projetsRepository;
    private final ProjectAlertConfigRepo alertConfigRepository;

    /**
     * Insère toutes les alertes par défaut à la création d'un projet
     */
    @Transactional
    public void insertDefaultAlertsForProject(Projets projet) {
        LocalDateTime now = LocalDateTime.now();

        // Parcourt chaque configuration définie dans notre template
        for (ProjectAlertTemplate template : ProjectAlertTemplate.values()) {
            
            ProjectAlertConfig config = new ProjectAlertConfig();
            
            // Association au projet
            config.setProjet(projet);
            
            // Mapping des valeurs du template
            config.setAlertType(template.getAlertType());
            config.setThresholdKey(template.getThresholdKey()); // Stocke "DAILY_WARNING", etc.
            config.setLevel(template.getLevel());
            config.setNumericValue(template.getNumericValue());
            config.setUnit(template.getUnit());
            
            // Valeurs par défaut demandées
            config.setStringValue(null);
            config.setDateValue(null);
            config.setStatus(AlertStatus.INACTIF); // Statut initialisé à INACTIF

            // Hydratation de l'objet@Embedded Initialisation
            Initialisation init = new Initialisation();
            init.setCreatedAt(now);
            init.setUpdatedAt(now);
            init.setRemoved(false);
            init.setArchive(false);
            config.setInitialisation(init);

            // Persistance en Base de données
            alertConfigRepository.save(config);
        }
    }

    @Override 
    @Transactional(readOnly = true)
    public List<AlertCountDTO> getAlertStatsByProject(String uniqueId) {
        // 1. Récupérer toutes les alertes actives du projet
        List<ProjectAlertConfig> activeAlerts = alertConfigRepository.findActiveAlertsByProjectUniqueId(uniqueId);

        // 2. Grouper par AlertType et compter les occurrences
        Map<AlertType, Long> countedMap = activeAlerts.stream()
                .collect(Collectors.groupingBy(
                        ProjectAlertConfig::getAlertType,
                        Collectors.counting()
                ));

        // 3. Construire la liste finale pour le Front (en s'assurant que chaque énum soit présent, même à 0)
        List<AlertCountDTO> stats = new ArrayList<>();
        for (AlertType type : AlertType.values()) {
            Long count = countedMap.getOrDefault(type, 0L);
            stats.add(new AlertCountDTO(type.name(), count));
        }

        return stats;
    }


    // Dans ton ProjectAlertConfigImpl (Classe)
    @Override
    @Transactional(readOnly = true)
    public List<ProjectAlertTableDTO> getAlertTableByProject(String uniqueId) {

        // Récupérer le projet
        Projets projet = projetsRepository.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Projet introuvable avec l'uniqueId: " + uniqueId));

        // Utilise ta requête existante avec JOIN FETCH
        List<ProjectAlertConfig> configs = alertConfigRepository.findByProjet(projet);

        return configs.stream()
            .map(ProjectAlertTableDTO::fromEntity)
            // 👇 TRI : On trie par l'état "enabled" inversé (true en premier, false en dernier)
            .sorted((dto1, dto2) -> Boolean.compare(dto2.isEnabled(), dto1.isEnabled()))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public ProjectAlertTableDTO toggleAlertStatus(Long alertId) {

        if (alertId != null) {
             ProjectAlertConfig alert = alertConfigRepository.findById(alertId)
                .orElseThrow(() -> new RuntimeException("Alerte introuvable avec l'ID: " + alertId));

        // Bascule le statut
        if (alert.getStatus() == AlertStatus.ACTIF) {
            alert.setStatus(AlertStatus.INACTIF);
        } else {
            alert.setStatus(AlertStatus.ACTIF);
        }
        
        // Met à jour la date de modification
        if (alert.getInitialisation() != null) {
            alert.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }

        return ProjectAlertTableDTO.fromEntity(alertConfigRepository.save(alert));
        } else {
            throw new RuntimeException("Alerte introuvable avec l'ID: " + alertId);
        }
    }


    @Transactional
    public String updateAllAlertConfigs(List<UpdateAlertRequestDTO> requests, String uniqueId) {
        if (requests == null || requests.isEmpty() || uniqueId == null) return "Données invalides";
      
        for (UpdateAlertRequestDTO req : requests) {
            
            Long id = req.getId();
            if (id != null) {
                // 1. CAS GÉNÉRAL : L'alerte existe déjà (Mortalité, Météo, Alimentation ou Vaccin existant)
                alertConfigRepository.findById(id).ifPresent(config -> {
                    // Mise à jour de l'état (Actif/Inactif)
                    config.setStatus(req.isEnabled() ? AlertStatus.ACTIF : AlertStatus.INACTIF);
                    
                    // Si c'est de la vaccination, on gère la valeur textuelle (Nom + Date si besoin)
                    if (config.getAlertType() == AlertType.VACCINATION) {
                        config.setStringValue(req.getStringValue());
                        config.setDateValue(LocalDate.parse(req.getDate()));
                    } else {
                        BigDecimal numericValue = BigDecimal.valueOf(req.getNumericValue());
                        // Sinon, on met à jour le seuil numérique
                        config.setNumericValue(numericValue);
                    }
                    
                    // Grâce à @Transactional, l'entité modifiée sera sauvée automatiquement à la fin de la méthode
                    alertConfigRepository.save(config); 
                });

            } else if (req.getAlertType() == AlertType.VACCINATION) {

                Projets projet = projetsRepository.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Projet introuvable avec l'uniqueId: " + uniqueId));

                // 2. CAS PARTICULIER : Nouvelle ligne de vaccination ajoutée depuis le Front (id est null)
                ProjectAlertConfig newVaccin = new ProjectAlertConfig();
                newVaccin.setAlertType(AlertType.VACCINATION);
                newVaccin.setThresholdKey(req.getThresholdKey()); // VACCINATION_SCHEDULED
                newVaccin.setStringValue(req.getStringValue());
                newVaccin.setStatus(req.isEnabled() ? AlertStatus.ACTIF : AlertStatus.INACTIF);

                newVaccin.setLevel(AlertLevel.CRITIQUE); // ou AlertLevel.WARNING selon ta logique métier

                if (req.getDate() != null && !req.getDate().isEmpty()) {
                    newVaccin.setDateValue(LocalDate.parse(req.getDate()));                    
                }
                 Initialisation init = new Initialisation();
                LocalDateTime now = LocalDateTime.now();

                init.setCreatedAt(now);
                init.setUpdatedAt(now);
                init.setRemoved(false);
                init.setArchive(false);

                newVaccin.setInitialisation(init);
                newVaccin.setProjet(projet); 

                alertConfigRepository.save(newVaccin);
            }
        }
        return "Données mises à jour avec succès";
    }

    @Override
    public String remove(Long alertId) {

        if (alertId != null) {
            alertConfigRepository.deleteById(alertId);
            return "Données mises à jour avec succès";
        } else {
            throw new RuntimeException("Alerte introuvable avec l'ID: " + alertId);
        }
    }



}
