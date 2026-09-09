package com.diafarms.ml.controllers;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.ProjetVenteReelDTO;
import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.TransactionStatsDTO;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.request.others.RejectTransactionRequest;
import com.diafarms.ml.request.update.TransactionUpdate;
import com.diafarms.ml.services.TransactionService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/transactions")
@RequiredArgsConstructor
public class TransactionControllers {

    private final TransactionService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<TransactionDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String statut,
            @RequestParam(required = false) String projetUniqueId,
            // Réservé à un ADMIN/SUPER_ADMIN : se place dans la vue d'un financier choisi
            // (voir TransactionServiceImpl.resolveProjetIdsScope). Un FINANCIER qui
            // l'envoie lui-même est ignoré côté service, toujours restreint à son propre
            // périmètre.
            @RequestParam(required = false) String financierUniqueId,
            // Réservé à un ADMIN/SUPER_ADMIN (page Ventes) : se place dans la vue d'un
            // vendeur choisi (voir TransactionServiceImpl.resolveVendeurScopeForList). Un
            // FINANCIER pur qui l'envoie lui-même est ignoré côté service, toujours
            // restreint à ses propres ventes.
            @RequestParam(required = false) String vendeurUniqueId,
            @RequestParam(required = false) String dateDebut,
            @RequestParam(required = false) String dateFin) {
        try {
            TypeTransaction typeEnum = (type != null && !type.isBlank() && !type.equalsIgnoreCase("tous"))
                    ? TypeTransaction.valueOf(type.toUpperCase()) : null;
            StatutTransaction statutEnum = (statut != null && !statut.isBlank() && !statut.equalsIgnoreCase("tous"))
                    ? StatutTransaction.valueOf(statut.toUpperCase()) : null;
            LocalDate dateDebutParam = (dateDebut != null && !dateDebut.isBlank()) ? LocalDate.parse(dateDebut) : null;
            LocalDate dateFinParam = (dateFin != null && !dateFin.isBlank()) ? LocalDate.parse(dateFin) : null;

            PaginatedResponse<TransactionDTO> response = service.list(page, size, search, typeEnum, statutEnum, projetUniqueId,
                    financierUniqueId, vendeurUniqueId, dateDebutParam, dateFinParam);
            return ApiResponse.createResponse("Liste des transactions récupérée", HttpStatus.OK, response, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Paramètre type/statut invalide", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors de la récupération des transactions", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<TransactionStatsDTO>> stats(
            @RequestParam(required = false) String financierUniqueId,
            @RequestParam(required = false) String dateDebut,
            @RequestParam(required = false) String dateFin) {
        try {
            LocalDate dateDebutParam = (dateDebut != null && !dateDebut.isBlank()) ? LocalDate.parse(dateDebut) : null;
            LocalDate dateFinParam = (dateFin != null && !dateFin.isBlank()) ? LocalDate.parse(dateFin) : null;
            return ApiResponse.createResponse("Statistiques récupérées", HttpStatus.OK,
                    service.getStats(financierUniqueId, dateDebutParam, dateFinParam), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors du calcul des statistiques", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/ventes-reel-par-projet")
    public ResponseEntity<ApiResponse<List<ProjetVenteReelDTO>>> ventesReelParProjet(
            @RequestParam(required = false) String dateDebut,
            @RequestParam(required = false) String dateFin) {
        try {
            LocalDate dateDebutParam = (dateDebut != null && !dateDebut.isBlank()) ? LocalDate.parse(dateDebut) : null;
            LocalDate dateFinParam = (dateFin != null && !dateFin.isBlank()) ? LocalDate.parse(dateFin) : null;
            return ApiResponse.createResponse("Ventes réelles par projet récupérées", HttpStatus.OK,
                    service.getVentesReelParProjet(dateDebutParam, dateFinParam), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors du calcul", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<TransactionDTO>> create(@RequestBody TransactionCreate request) {
        try {
            return ApiResponse.createResponse("Transaction créée avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<TransactionDTO>> update(@PathVariable String uniqueId, @RequestBody TransactionUpdate request) {
        try {
            return ApiResponse.createResponse("Transaction modifiée avec succès", HttpStatus.OK, service.update(uniqueId, request), null);
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

    @PutMapping("/demander-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<TransactionDTO>> demanderSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Demande de suppression envoyée — en attente de validation", HttpStatus.OK, service.demanderSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/confirmer-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<TransactionDTO>> confirmerSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Suppression confirmée", HttpStatus.OK, service.confirmerSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/annuler-demande-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<TransactionDTO>> annulerDemandeSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Demande de suppression refusée", HttpStatus.OK, service.annulerDemandeSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/valider/{uniqueId}")
    public ResponseEntity<ApiResponse<TransactionDTO>> valider(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Transaction validée avec succès", HttpStatus.OK, service.valider(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/rejeter/{uniqueId}")
    public ResponseEntity<ApiResponse<TransactionDTO>> rejeter(@PathVariable String uniqueId, @RequestBody RejectTransactionRequest request) {
        try {
            return ApiResponse.createResponse("Transaction rejetée avec succès", HttpStatus.OK, service.rejeter(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
