package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.PaiementSalaireDTO;
import com.diafarms.ml.DTO.SalaireDTO;
import com.diafarms.ml.DTO.TauxSalaireDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.SalaireDefinirRequest;
import com.diafarms.ml.request.others.SalairePaiementUpdateRequest;
import com.diafarms.ml.request.others.SalairePayerRequest;
import com.diafarms.ml.services.SalaireService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/salaires")
@RequiredArgsConstructor
public class SalaireController {

    private final SalaireService service;

    @PostMapping("/definir")
    public ResponseEntity<ApiResponse<SalaireDTO>> definir(@RequestBody SalaireDefinirRequest request) {
        try {
            return ApiResponse.createResponse("Salaire de base enregistré", HttpStatus.OK, service.definir(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/payer")
    public ResponseEntity<ApiResponse<PaiementSalaireDTO>> payer(@RequestBody SalairePayerRequest request) {
        try {
            return ApiResponse.createResponse("Salaire payé avec succès", HttpStatus.CREATED, service.payer(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/paiements/{paiementUniqueId}")
    public ResponseEntity<ApiResponse<PaiementSalaireDTO>> modifierPaiement(
            @PathVariable String paiementUniqueId,
            @RequestBody SalairePaiementUpdateRequest request) {
        try {
            return ApiResponse.createResponse("Paiement corrigé", HttpStatus.OK, service.modifierPaiement(paiementUniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @DeleteMapping("/paiements/{paiementUniqueId}")
    public ResponseEntity<ApiResponse<String>> supprimerPaiement(@PathVariable String paiementUniqueId) {
        try {
            service.supprimerPaiement(paiementUniqueId);
            return ApiResponse.createResponse("Paiement supprimé", HttpStatus.OK, "OK", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<SalaireDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.createResponse("Liste des salaires récupérée", HttpStatus.OK, service.list(page, size), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/select")
    public ResponseEntity<ApiResponse<List<SalaireDTO>>> select() {
        try {
            return ApiResponse.createResponse("Grille salariale récupérée", HttpStatus.OK, service.select(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/paiements/{paiementUniqueId}/pdf")
    public ResponseEntity<byte[]> bulletinPdf(@PathVariable String paiementUniqueId) {
        byte[] pdf = service.genererBulletinPdf(paiementUniqueId);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(paiementUniqueId + ".pdf").build());
        return new ResponseEntity<>(pdf, headers, HttpStatus.OK);
    }

    @GetMapping("/{employeUniqueId}/taux")
    public ResponseEntity<ApiResponse<TauxSalaireDTO>> tauxPourPeriode(
            @PathVariable String employeUniqueId,
            @RequestParam String periode) {
        try {
            return ApiResponse.createResponse("Taux récupéré", HttpStatus.OK, service.getTauxPourPeriode(employeUniqueId, periode), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/{employeUniqueId}/paiements")
    public ResponseEntity<ApiResponse<PaginatedResponse<PaiementSalaireDTO>>> listPaiements(
            @PathVariable String employeUniqueId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "12") int size) {
        try {
            return ApiResponse.createResponse("Historique des paiements récupéré", HttpStatus.OK, service.listPaiements(employeUniqueId, page, size), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
