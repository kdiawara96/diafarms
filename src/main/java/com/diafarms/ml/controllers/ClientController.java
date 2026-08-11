package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.ClientDTO;
import com.diafarms.ml.DTO.ClientReportDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ClientCreate;
import com.diafarms.ml.request.others.PayerDetteClientRequest;
import com.diafarms.ml.services.ClientService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/clients")
@RequiredArgsConstructor
public class ClientController {

    private final ClientService service;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<ClientDTO>> create(@RequestBody ClientCreate request) {
        try {
            return ApiResponse.createResponse("Client créé avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<ClientDTO>> update(@PathVariable String uniqueId, @RequestBody ClientCreate request) {
        try {
            return ApiResponse.createResponse("Client mis à jour", HttpStatus.OK, service.update(uniqueId, request), null);
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

    @GetMapping("/select")
    public ResponseEntity<ApiResponse<List<ClientDTO>>> select() {
        try {
            return ApiResponse.createResponse("Liste des clients récupérée", HttpStatus.OK, service.select(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/{uniqueId}/report")
    public ResponseEntity<ApiResponse<ClientReportDTO>> report(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Rapport client récupéré", HttpStatus.OK, service.getReport(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/{uniqueId}/payer-dette")
    public ResponseEntity<ApiResponse<ClientDTO>> payerDette(@PathVariable String uniqueId, @RequestBody PayerDetteClientRequest request) {
        try {
            return ApiResponse.createResponse("Paiement enregistré", HttpStatus.OK,
                    service.payerDette(uniqueId, request.getMontant(), request.getDescription()), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<ClientDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search) {
        try {
            return ApiResponse.createResponse("Liste des clients récupérée", HttpStatus.OK, service.list(page, size, search), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
