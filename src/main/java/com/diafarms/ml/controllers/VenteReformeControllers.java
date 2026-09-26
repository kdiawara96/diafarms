package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.StockReformeDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;
import com.diafarms.ml.services.VenteReformeService;

import lombok.RequiredArgsConstructor;

// Vente réforme (Finance) : à l'échelle de la ferme entière — pas de paramètre
// projetUniqueId, contrairement aux endpoints Production (/reformes/*).
@RestController
@RequestMapping("/diafarms/api/v1/ventes-reforme")
@RequiredArgsConstructor
public class VenteReformeControllers {

    private final VenteReformeService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<VenteReformeDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        try {
            PaginatedResponse<VenteReformeDTO> response = service.list(page, size);
            return ApiResponse.createResponse("Liste des ventes réforme récupérée", HttpStatus.OK, response, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors de la récupération des ventes réforme", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/stock")
    public ResponseEntity<ApiResponse<StockReformeDTO>> getStock() {
        try {
            return ApiResponse.createResponse("Stock de réforme récupéré", HttpStatus.OK, service.getStock(), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Statistiques réforme : sujets vendus, poids vendu au kilo, prix moyen au kilo et par
    // tête, poids moyen par sujet — voir StatsReformeDTO. Dates "yyyy-MM-dd" optionnelles.
    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<com.diafarms.ml.DTO.StatsReformeDTO>> stats(
            @RequestParam(required = false) String dateDebut,
            @RequestParam(required = false) String dateFin,
            @RequestParam(required = false) String projetUniqueId) {
        try {
            java.time.LocalDate deb = parseDate(dateDebut, "dateDebut");
            java.time.LocalDate fin = parseDate(dateFin, "dateFin");
            return ApiResponse.createResponse("Statistiques réforme récupérées", HttpStatus.OK,
                    service.stats(deb, fin, projetUniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    private static java.time.LocalDate parseDate(String raw, String nom) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return java.time.LocalDate.parse(raw.trim());
        } catch (java.time.format.DateTimeParseException e) {
            throw new IllegalArgumentException("Date invalide pour " + nom + " (attendu AAAA-MM-JJ) : " + raw);
        }
    }

    @GetMapping("/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> detail(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Vente réforme récupérée", HttpStatus.OK, service.detail(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> create(@RequestBody VenteReformeCreate request) {
        try {
            return ApiResponse.createResponse("Vente réforme enregistrée avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> update(@PathVariable String uniqueId, @RequestBody VenteReformeUpdate request) {
        try {
            return ApiResponse.createResponse("Vente réforme modifiée avec succès", HttpStatus.OK, service.update(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(@PathVariable String uniqueId,
                                                               @RequestBody(required = false) MotifSuppressionRequest request) {
        try {
            return ApiResponse.createResponse("Opération réussie", HttpStatus.OK,
                    service.deleteOrRecover(uniqueId, request != null ? request.getMotif() : null), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Suppression en deux temps (demande puis confirmation) — voir VenteReformeImpl :
    // jamais le vendeur, même pour sa propre vente.
    @PutMapping("/demander-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> demanderSuppression(@PathVariable String uniqueId,
                                                                    @RequestBody(required = false) MotifSuppressionRequest request) {
        try {
            return ApiResponse.createResponse("Demande de suppression envoyée", HttpStatus.OK,
                    service.demanderSuppression(uniqueId, request != null ? request.getMotif() : null), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/confirmer-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> confirmerSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Vente supprimée", HttpStatus.OK, service.confirmerSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/annuler-demande-suppression/{uniqueId}")
    public ResponseEntity<ApiResponse<VenteReformeDTO>> annulerDemandeSuppression(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Demande de suppression refusée", HttpStatus.OK, service.annulerDemandeSuppression(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
