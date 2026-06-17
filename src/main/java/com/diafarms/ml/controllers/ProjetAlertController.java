package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.AlertCountDTO;
import com.diafarms.ml.DTO.ProjectAlertTableDTO;
import com.diafarms.ml.models.OccupationBatiment;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.OccupationService;
import com.diafarms.ml.services.ProjectAlertConfigService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/alertes") 
@RequiredArgsConstructor
public class ProjetAlertController {

      private final ProjectAlertConfigService services;

    @GetMapping("/AlertCount/{uniqueId}")
    public ResponseEntity<ApiResponse<List<AlertCountDTO>>> getAlertStats(@PathVariable String uniqueId) {
        try {
            List<AlertCountDTO> result = services.getAlertStatsByProject(uniqueId);
            return ApiResponse.createResponse("Recuperation des alertes avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }


    // 1. Récupération pour le tableau
    @GetMapping("/table/{uniqueId}")
    public ResponseEntity<ApiResponse<List<ProjectAlertTableDTO>>> getAlertTable(@PathVariable String uniqueId) {
        List<ProjectAlertTableDTO> result = services.getAlertTableByProject(uniqueId);
        return ApiResponse.createResponse("Liste des alertes chargée", HttpStatus.OK, result, null);
    }

    // 2. Toggle de l'état (Actif/Inactif)
    @PutMapping("/toggle/{alertId}")
    public ResponseEntity<ApiResponse<ProjectAlertTableDTO>> toggleAlert(@PathVariable Long alertId) {
        ProjectAlertTableDTO updated = services.toggleAlertStatus(alertId);
        return ApiResponse.createResponse("Statut de l'alerte mis à jour", HttpStatus.OK, updated, null);
    }
    
}
