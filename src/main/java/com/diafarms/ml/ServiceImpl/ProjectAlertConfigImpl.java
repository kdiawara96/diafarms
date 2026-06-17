package com.diafarms.ml.ServiceImpl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.AlertCountDTO;
import com.diafarms.ml.DTO.ProjectAlertTableDTO;
import com.diafarms.ml.commons.Initialisation;
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
        
        // Si tu veux TOUTES les alertes (actives ET inactives) pour la configuration, 
        // assure-toi que ta méthode JPA ne filtre pas sur le statut si tu veux pouvoir les réactiver !
        
        return configs.stream()
                .map(ProjectAlertTableDTO::fromEntity)
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
}
