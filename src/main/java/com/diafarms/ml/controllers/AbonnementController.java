package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.AbonnementConfigDTO;
import com.diafarms.ml.DTO.AbonnementDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.AbonnementConfigUpdateRequest;
import com.diafarms.ml.request.others.DeclarerPaiementAbonnementRequest;
import com.diafarms.ml.request.others.RejeterPaiementAbonnementRequest;
import com.diafarms.ml.services.AbonnementService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/abonnements")
@RequiredArgsConstructor
public class AbonnementController {

    private final AbonnementService service;

    @GetMapping("/moi")
    public ResponseEntity<ApiResponse<AbonnementDTO>> moi() {
        try {
            return ApiResponse.createResponse("Statut d'abonnement récupéré", HttpStatus.OK, service.getMoi(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/declarer-paiement")
    public ResponseEntity<ApiResponse<PaiementAbonnementDTO>> declarerPaiement(@RequestBody DeclarerPaiementAbonnementRequest request) {
        try {
            return ApiResponse.createResponse("Déclaration de paiement enregistrée", HttpStatus.CREATED, service.declarerPaiement(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/config")
    public ResponseEntity<ApiResponse<AbonnementConfigDTO>> getConfig() {
        try {
            return ApiResponse.createResponse("Configuration tarifaire récupérée", HttpStatus.OK, service.getConfig(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/config")
    public ResponseEntity<ApiResponse<AbonnementConfigDTO>> updateConfig(@RequestBody AbonnementConfigUpdateRequest request) {
        try {
            return ApiResponse.createResponse("Configuration tarifaire mise à jour", HttpStatus.OK, service.updateConfig(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/en-attente")
    public ResponseEntity<ApiResponse<PaginatedResponse<PaiementAbonnementDTO>>> enAttente(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.createResponse("Déclarations en attente récupérées", HttpStatus.OK, service.listEnAttente(page, size), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/{uniqueId}/valider")
    public ResponseEntity<ApiResponse<PaiementAbonnementDTO>> valider(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Paiement validé, abonnement activé", HttpStatus.OK, service.valider(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/{uniqueId}/rejeter")
    public ResponseEntity<ApiResponse<PaiementAbonnementDTO>> rejeter(@PathVariable String uniqueId, @RequestBody RejeterPaiementAbonnementRequest request) {
        try {
            return ApiResponse.createResponse("Paiement rejeté", HttpStatus.OK, service.rejeter(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
