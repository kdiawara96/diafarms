package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.AlertCountDTO;
import com.diafarms.ml.DTO.ProjectAlertTableDTO;
import com.diafarms.ml.DTO.UpdateAlertRequestDTO;
import com.diafarms.ml.models.Projets;

public interface ProjectAlertConfigService {
    void insertDefaultAlertsForProject(Projets projet);
    public List<AlertCountDTO> getAlertStatsByProject(String uniqueId);
    List<ProjectAlertTableDTO> getAlertTableByProject(String uniqueId);
    ProjectAlertTableDTO toggleAlertStatus(Long alertId);
    String updateAllAlertConfigs(List<UpdateAlertRequestDTO> requests, String uniqueId);
}
