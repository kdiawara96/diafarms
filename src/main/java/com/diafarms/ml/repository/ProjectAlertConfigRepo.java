package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.diafarms.ml.models.ProjectAlertConfig;
import com.diafarms.ml.models.Projets;

public interface ProjectAlertConfigRepo extends JpaRepository<ProjectAlertConfig, Long> {
    
   // Récupérer les alertes ACTIVES avec toutes leurs données
    @Query("SELECT a FROM ProjectAlertConfig a " +
        "JOIN FETCH a.projet p " +
        "WHERE p.uniqueId = :uniqueId " +
        "AND a.status = com.diafarms.ml.enums.AlertStatus.ACTIF")
    List<ProjectAlertConfig> findActiveAlertsByProjectUniqueId(@Param("uniqueId") String uniqueId);

    List<ProjectAlertConfig>  findByProjet(Projets projet);
}
