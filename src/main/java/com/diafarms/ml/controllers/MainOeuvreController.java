package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.AffectationPersonnelDTO;
import com.diafarms.ml.DTO.CoutMainOeuvreDTO;
import com.diafarms.ml.ServiceImpl.MainOeuvreService;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.AffectationPersonnelCreate;

import lombok.RequiredArgsConstructor;

// Affectations du personnel aux projets et coût de main-d'œuvre par projet
// (voir MainOeuvreService).
@RestController
@RequestMapping("/diafarms/api/v1/main-oeuvre")
@RequiredArgsConstructor
public class MainOeuvreController {

    private final MainOeuvreService service;

    @GetMapping("/personnel/{uniqueId}/affectations")
    public ResponseEntity<ApiResponse<List<AffectationPersonnelDTO>>> affectations(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Affectations récupérées", HttpStatus.OK, service.affectations(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/personnel/{uniqueId}/affectations")
    public ResponseEntity<ApiResponse<AffectationPersonnelDTO>> affecter(@PathVariable String uniqueId, @RequestBody AffectationPersonnelCreate data) {
        try {
            return ApiResponse.createResponse("Employé affecté", HttpStatus.CREATED, service.affecter(uniqueId, data), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/affectations/{uniqueId}")
    public ResponseEntity<ApiResponse<AffectationPersonnelDTO>> modifier(@PathVariable String uniqueId, @RequestBody AffectationPersonnelCreate data) {
        try {
            return ApiResponse.createResponse("Affectation modifiée", HttpStatus.OK, service.modifier(uniqueId, data), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @DeleteMapping("/affectations/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> supprimer(@PathVariable String uniqueId) {
        try {
            service.supprimer(uniqueId);
            return ApiResponse.createResponse("Affectation supprimée", HttpStatus.OK, "Affectation supprimée", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/projets/{projetUniqueId}/cout")
    public ResponseEntity<ApiResponse<CoutMainOeuvreDTO>> cout(@PathVariable String projetUniqueId) {
        try {
            return ApiResponse.createResponse("Coût de main-d'œuvre", HttpStatus.OK, service.coutProjet(projetUniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
