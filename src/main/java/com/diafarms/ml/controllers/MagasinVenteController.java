package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.MagasinVenteDTO;
import com.diafarms.ml.DTO.StockMagasinDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.MagasinVenteCreate;
import com.diafarms.ml.services.MagasinVenteService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/magasins")
@RequiredArgsConstructor
public class MagasinVenteController {

    private final MagasinVenteService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<MagasinVenteDTO>>> list() {
        try {
            return ApiResponse.createResponse("Liste des magasins récupérée", HttpStatus.OK, service.list(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<MagasinVenteDTO>> create(@RequestBody MagasinVenteCreate request) {
        try {
            return ApiResponse.createResponse("Magasin créé avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<MagasinVenteDTO>> update(@PathVariable String uniqueId, @RequestBody MagasinVenteCreate request) {
        try {
            return ApiResponse.createResponse("Magasin mis à jour", HttpStatus.OK, service.update(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse(service.deleteOrRecover(uniqueId), HttpStatus.OK, null, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/{uniqueId}/stock")
    public ResponseEntity<ApiResponse<StockMagasinDTO>> getStock(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Stock du magasin récupéré", HttpStatus.OK, service.getStock(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
