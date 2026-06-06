package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import com.diafarms.ml.DTO.RaceDTO;
import com.diafarms.ml.models.Race;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.RaceServices;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.VaccinationDTO;
import com.diafarms.ml.request.create.VaccinCreate;
import com.diafarms.ml.request.update.VaccinUpdate;
import com.diafarms.ml.services.VaccinationService;


@RestController
@RequestMapping("/diafarms/api/v1/vaccinations")
@RequiredArgsConstructor
public class VaccinationControllers {

    private final VaccinationService services;


        // ============================================================
    // CREATE
    // ============================================================
    @PostMapping("/create/{uniqueIdProjet}")
    public ResponseEntity<ApiResponse<VaccinationDTO>> create(
            @RequestBody VaccinCreate request,
            @PathVariable("uniqueIdProjet") String uniqueIdProjet) {
        try {
            VaccinationDTO result = services.save(request, uniqueIdProjet);
            return ApiResponse.createResponse("Vaccination créée avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // ============================================================
    // UPDATE
    // ============================================================
    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<VaccinationDTO>> update(
            @RequestBody VaccinUpdate request,
            @PathVariable("uniqueId") String uniqueId) {
        try {
            VaccinationDTO result = services.update(uniqueId, request);
            return ApiResponse.createResponse("Vaccination mise à jour avec succès", HttpStatus.OK, result, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur de validation", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // ============================================================
    // DELETE (Soft Delete)
    // ============================================================
    @DeleteMapping("/delete/{uniqueId}")
    public ResponseEntity<ApiResponse<VaccinationDTO>> delete(
            @PathVariable("uniqueId") String uniqueId) {
        try {
            VaccinationDTO result = services.delete(uniqueId);
            return ApiResponse.createResponse("Vaccination supprimée avec succès", HttpStatus.OK, result, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }


    // ============================================================
    // LIST BY PROJECT
    // ============================================================
    @GetMapping("/list-by-projet/{uniqueIdProjet}")
    public ResponseEntity<ApiResponse<List<VaccinationDTO>>> listByProjet(
            @PathVariable("uniqueIdProjet") String uniqueIdProjet) {
        try {
            List<VaccinationDTO> result = services.findByProjetUniqueId(uniqueIdProjet);
            return ApiResponse.createResponse("Liste récupérée", HttpStatus.OK, result, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
    
}
