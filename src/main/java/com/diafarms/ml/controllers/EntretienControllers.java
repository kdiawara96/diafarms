package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.EntretienDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.EntretienCreate;
import com.diafarms.ml.request.update.EntretienUpdate;
import com.diafarms.ml.services.EntretienService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/entretiens")
@RequiredArgsConstructor
public class EntretienControllers {

    private final EntretienService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<EntretienDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String batimentUniqueId,
            @RequestParam(required = false) String niveau,
            @RequestParam(required = false) String type) {
        try {
            PaginatedResponse<EntretienDTO> response = service.list(page, size, search, batimentUniqueId, niveau, type);
            return ApiResponse.createResponse("Liste des entretiens récupérée", HttpStatus.OK, response, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors de la récupération des entretiens", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<EntretienDTO>> create(@RequestBody EntretienCreate request) {
        try {
            return ApiResponse.createResponse("Entretien enregistré avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<EntretienDTO>> update(@PathVariable String uniqueId, @RequestBody EntretienUpdate request) {
        try {
            return ApiResponse.createResponse("Entretien modifié avec succès", HttpStatus.OK, service.update(uniqueId, request), null);
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
