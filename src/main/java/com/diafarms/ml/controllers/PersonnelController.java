package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.PersonnelDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.PersonnelCreate;
import com.diafarms.ml.services.PersonnelService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/personnel")
@RequiredArgsConstructor
public class PersonnelController {

    private final PersonnelService service;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<PersonnelDTO>> create(@RequestBody PersonnelCreate request) {
        try {
            return ApiResponse.createResponse("Personnel créé avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<PersonnelDTO>> update(@PathVariable String uniqueId, @RequestBody PersonnelCreate request) {
        try {
            return ApiResponse.createResponse("Personnel mis à jour", HttpStatus.OK, service.update(uniqueId, request), null);
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
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/select")
    public ResponseEntity<ApiResponse<List<PersonnelDTO>>> select() {
        try {
            return ApiResponse.createResponse("Liste du personnel récupérée", HttpStatus.OK, service.select(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
