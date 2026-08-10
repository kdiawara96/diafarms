package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.MagasinTransfertDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.MagasinTransfertCreate;
import com.diafarms.ml.services.MagasinTransfertService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/magasin-transferts")
@RequiredArgsConstructor
public class MagasinTransfertController {

    private final MagasinTransfertService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<MagasinTransfertDTO>>> list(
            @RequestParam String magasinUniqueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            return ApiResponse.createResponse("Liste des transferts récupérée", HttpStatus.OK, service.list(magasinUniqueId, page, size), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<List<MagasinTransfertDTO>>> create(@RequestBody MagasinTransfertCreate request) {
        try {
            return ApiResponse.createResponse("Transfert enregistré avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // REFORME : disponible pour UN PROJET précis (source directe).
    @GetMapping("/disponible")
    public ResponseEntity<ApiResponse<Integer>> disponible(@RequestParam String projetUniqueId, @RequestParam String type) {
        try {
            return ApiResponse.createResponse("Stock disponible à transférer récupéré", HttpStatus.OK,
                    service.disponibleATransfererDepuisProjet(projetUniqueId, type), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // OEUFS : disponible pour UN MAGASIN DE STOCKAGE (tous projets contributeurs confondus).
    // Route conservée pour compat des clients existants (mobile), le paramètre a changé de sens.
    @GetMapping("/disponible-batiment")
    public ResponseEntity<ApiResponse<Integer>> disponibleBatiment(@RequestParam String magasinStockageUniqueId) {
        try {
            return ApiResponse.createResponse("Stock disponible à transférer récupéré", HttpStatus.OK,
                    service.disponibleATransfererDepuisMagasinStockage(magasinStockageUniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
