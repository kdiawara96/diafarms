package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.VenteDiverseDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.VenteDiverseCreate;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.request.update.VenteDiverseUpdate;
import com.diafarms.ml.services.VenteDiverseService;

import lombok.RequiredArgsConstructor;

// Vente de fientes / autre vente — voir VenteDiverse.
@RestController
@RequestMapping("/diafarms/api/v1/ventes-diverses")
@RequiredArgsConstructor
public class VenteDiverseControllers {

    private final VenteDiverseService service;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<VenteDiverseDTO>> create(@RequestBody VenteDiverseCreate request) {
        try {
            return ApiResponse.createResponse("Vente enregistrée avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteDiverseDTO>> update(@PathVariable String uniqueId, @RequestBody VenteDiverseUpdate request) {
        try {
            return ApiResponse.createResponse("Vente modifiée avec succès", HttpStatus.OK, service.update(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(@PathVariable String uniqueId,
                                                               @RequestBody(required = false) MotifSuppressionRequest request) {
        try {
            return ApiResponse.createResponse("Opération réussie", HttpStatus.OK,
                    service.deleteOrRecover(uniqueId, request != null ? request.getMotif() : null), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/demander-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteDiverseDTO>> demanderSuppression(@PathVariable String uniqueId,
                                                                            @RequestBody(required = false) MotifSuppressionRequest request) {
        try {
            return ApiResponse.createResponse("Demande de suppression envoyée", HttpStatus.OK,
                    service.demanderSuppression(uniqueId, request != null ? request.getMotif() : null), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/confirmer-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteDiverseDTO>> confirmerSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Vente supprimée", HttpStatus.OK, service.confirmerSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/annuler-demande-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteDiverseDTO>> annulerDemandeSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Demande de suppression refusée", HttpStatus.OK, service.annulerDemandeSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
