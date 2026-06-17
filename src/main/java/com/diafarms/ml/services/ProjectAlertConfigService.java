package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.AlertCountDTO;
import com.diafarms.ml.models.Projets;

public interface ProjectAlertConfigService {
    void insertDefaultAlertsForProject(Projets projet);
    public List<AlertCountDTO> getAlertStatsByProject(String uniqueId);
}
