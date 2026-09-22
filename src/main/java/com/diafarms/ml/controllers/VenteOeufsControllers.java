package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.StockOeufsDTO;
import com.diafarms.ml.DTO.VenteOeufsDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.update.VenteOeufsUpdate;
import com.diafarms.ml.services.VenteOeufsService;

import lombok.RequiredArgsConstructor;

// Vente d'œufs (Finance) : à l'échelle de la ferme entière, pas d'un projet précis —
// pas de paramètre projetUniqueId ici, contrairement aux endpoints Production.
@RestController
@RequestMapping("/diafarms/api/v1/ventes-oeufs")
@RequiredArgsConstructor
public class VenteOeufsControllers {

    private final VenteOeufsService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<VenteOeufsDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            PaginatedResponse<VenteOeufsDTO> response = service.list(page, size);
            return ApiResponse.createResponse("Liste des ventes d'œufs récupérée", HttpStatus.OK, response, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors de la récupération des ventes d'œufs", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/stock")
    public ResponseEntity<ApiResponse<StockOeufsDTO>> getStock() {
        try {
            return ApiResponse.createResponse("Stock d'œufs récupéré", HttpStatus.OK, service.getStock(), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<VenteOeufsDTO>> create(@RequestBody VenteOeufsCreate request) {
        try {
            return ApiResponse.createResponse("Vente d'œufs enregistrée avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteOeufsDTO>> update(@PathVariable String uniqueId, @RequestBody VenteOeufsUpdate request) {
        try {
            return ApiResponse.createResponse("Vente d'œufs modifiée avec succès", HttpStatus.OK, service.update(uniqueId, request), null);
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

    // Suppression en deux temps (demande puis confirmation) — voir VenteOeufsImpl :
    // jamais le vendeur, même pour sa propre vente.
    @PutMapping("/demander-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteOeufsDTO>> demanderSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Demande de suppression envoyée", HttpStatus.OK, service.demanderSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/confirmer-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteOeufsDTO>> confirmerSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Vente supprimée", HttpStatus.OK, service.confirmerSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/annuler-demande-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteOeufsDTO>> annulerDemandeSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Demande de suppression refusée", HttpStatus.OK, service.annulerDemandeSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
