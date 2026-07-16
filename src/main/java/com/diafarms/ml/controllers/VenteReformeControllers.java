package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.EffectifReformeDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/ventes-reforme")
@RequiredArgsConstructor
public class VenteReformeControllers {

    private final VenteReformeService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<VenteReformeDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String projetUniqueId,
            @RequestParam(required = false) String batimentUniqueId) {
        try {
            PaginatedResponse<VenteReformeDTO> response = service.list(page, size, projetUniqueId, batimentUniqueId);
            return ApiResponse.createResponse("Liste des ventes réforme récupérée", HttpStatus.OK, response, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors de la récupération des ventes réforme", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/effectif/{projetUniqueId}")
    public ResponseEntity<ApiResponse<EffectifReformeDTO>> getEffectif(@PathVariable String projetUniqueId) {
        try {
            return ApiResponse.createResponse("Effectif vivant récupéré", HttpStatus.OK, service.getEffectif(projetUniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> create(@RequestBody VenteReformeCreate request) {
        try {
            return ApiResponse.createResponse("Vente réforme enregistrée avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> update(@PathVariable String uniqueId, @RequestBody VenteReformeUpdate request) {
        try {
            return ApiResponse.createResponse("Vente réforme modifiée avec succès", HttpStatus.OK, service.update(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Opération réussie", HttpStatus.OK, service.deleteOrRecover(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
