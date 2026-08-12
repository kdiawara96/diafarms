package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.CommandeDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.CommandeCreate;
import com.diafarms.ml.services.CommandeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/commandes")
@RequiredArgsConstructor
public class CommandeController {

    private final CommandeService service;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<CommandeDTO>> create(@RequestBody CommandeCreate request) {
        try {
            return ApiResponse.createResponse("Commande créée avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<CommandeDTO>> update(@PathVariable String uniqueId, @RequestBody CommandeCreate request) {
        try {
            return ApiResponse.createResponse("Commande mise à jour", HttpStatus.OK, service.update(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/{uniqueId}/confirmer")
    public ResponseEntity<ApiResponse<CommandeDTO>> confirmer(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Commande confirmée", HttpStatus.OK, service.confirmer(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/{uniqueId}/annuler")
    public ResponseEntity<ApiResponse<CommandeDTO>> annuler(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Commande annulée", HttpStatus.OK, service.annuler(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/{uniqueId}/convertir-en-vente")
    public ResponseEntity<ApiResponse<CommandeDTO>> convertirEnVente(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Commande convertie en vente", HttpStatus.OK, service.convertirEnVente(uniqueId), null);
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

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<CommandeDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String statut,
            @RequestParam(required = false) String clientUniqueId) {
        try {
            return ApiResponse.createResponse("Liste des commandes récupérée", HttpStatus.OK, service.list(page, size, statut, clientUniqueId), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
