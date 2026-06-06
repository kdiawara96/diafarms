package com.diafarms.ml.controllers;

import com.diafarms.ml.DTO.AlimentationDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.AlimentationCreate;
import com.diafarms.ml.services.AlimentationService;
import lombok.RequiredArgsConstructor;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping("/diafarms/api/v1/alimentations")
@RequiredArgsConstructor
public class AlimentationControllers {

    private final AlimentationService services;


    // ============================================================
    // CREATE
    // ============================================================
    @PostMapping("/create/{uniqueIdProjet}")
    public ResponseEntity<ApiResponse<AlimentationDTO>> create(
            @RequestBody AlimentationCreate request,
            @PathVariable("uniqueIdProjet") String uniqueIdProjet) {
        try {
            AlimentationDTO result = services.save(request, uniqueIdProjet);
            return ApiResponse.createResponse("Alimentation créée avec succès", HttpStatus.OK, result, null);
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
    public ResponseEntity<ApiResponse<AlimentationDTO>> update(
            @RequestBody AlimentationCreate request,
            @PathVariable("uniqueId") String uniqueId) {
        try {
            AlimentationDTO result = services.update(uniqueId, request);
            return ApiResponse.createResponse("Alimentation mise à jour avec succès", HttpStatus.OK, result, null);
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
    public ResponseEntity<ApiResponse<AlimentationDTO>> delete(
            @PathVariable("uniqueId") String uniqueId) {
        try {
            AlimentationDTO result = services.delete(uniqueId);
            return ApiResponse.createResponse("Alimentation supprimée avec succès", HttpStatus.OK, result, null);
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
    public ResponseEntity<ApiResponse<List<AlimentationDTO>>> listByProjet(
            @PathVariable("uniqueIdProjet") String uniqueIdProjet) {
        try {
            List<AlimentationDTO> result = services.findByProjetUniqueId(uniqueIdProjet);
            return ApiResponse.createResponse("Liste récupérée", HttpStatus.OK, result, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // ============================================================
    // GET BY UNIQUE ID
    // ============================================================
    @GetMapping("/{uniqueId}")
    public ResponseEntity<ApiResponse<AlimentationDTO>> getByUniqueId(
            @PathVariable("uniqueId") String uniqueId) {
        try {
            AlimentationDTO result = services.findByUniqueId(uniqueId);
            return ApiResponse.createResponse("Alimentation trouvée", HttpStatus.OK, result, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Alimentation non trouvée", HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
    
}
