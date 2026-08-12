package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.FactureDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.FactureGenerateRequest;
import com.diafarms.ml.request.others.FactureMarquerPayeeRequest;
import com.diafarms.ml.services.FactureService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/factures")
@RequiredArgsConstructor
public class FactureController {

    private final FactureService service;

    @PostMapping("/generer")
    public ResponseEntity<ApiResponse<FactureDTO>> generer(@RequestBody FactureGenerateRequest request) {
        try {
            return ApiResponse.createResponse("Facture générée avec succès", HttpStatus.CREATED, service.genererDepuis(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/{uniqueId}/marquer-payee")
    public ResponseEntity<ApiResponse<FactureDTO>> marquerPayee(@PathVariable String uniqueId,
                                                                  @RequestBody(required = false) FactureMarquerPayeeRequest request) {
        try {
            Double montant = request != null ? request.getMontant() : null;
            return ApiResponse.createResponse("Facture mise à jour", HttpStatus.OK, service.marquerPayee(uniqueId, montant), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/{uniqueId}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable String uniqueId) {
        byte[] pdf = service.genererPdf(uniqueId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(uniqueId + ".pdf").build());
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<FactureDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String statut,
            @RequestParam(required = false) String clientUniqueId) {
        try {
            return ApiResponse.createResponse("Liste des factures récupérée", HttpStatus.OK, service.list(page, size, statut, clientUniqueId), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
