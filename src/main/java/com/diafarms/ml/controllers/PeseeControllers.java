package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.EvolutionPoidsDTO;
import com.diafarms.ml.DTO.SessionPeseeDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.SessionPeseeSyncRequest;
import com.diafarms.ml.services.SessionPeseeService;

import lombok.RequiredArgsConstructor;

// Sessions de pesée (mobile hors ligne → synchro idempotente). Lecture : tout
// utilisateur de la ferme ; synchro : ADMIN, SUPER_ADMIN, RESPONSABLE, PRODUCTION.
@RestController
@RequestMapping("/diafarms/api/v1/pesees")
@RequiredArgsConstructor
public class PeseeControllers {

    private final SessionPeseeService service;

    @PostMapping("/sessions/sync")
    public ResponseEntity<ApiResponse<SessionPeseeDTO>> sync(@RequestBody SessionPeseeSyncRequest request) {
        try {
            return ApiResponse.createResponse("Session de pesée synchronisée", HttpStatus.OK, service.sync(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (DataIntegrityViolationException e) {
            // Identifiant déjà pris (envoi concurrent sur un autre projet, etc.) : le
            // téléphone pourra renvoyer, le second passage verra l'état enregistré.
            return ApiResponse.createResponse("Conflit d'enregistrement, veuillez réessayer", HttpStatus.CONFLICT, null,
                    List.of("Identifiant de session ou de pesée déjà utilisé."));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/sessions/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<SessionPeseeDTO>>> list(
            @RequestParam(required = false) String projetUniqueId,
            @RequestParam(required = false) String statut,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        try {
            return ApiResponse.createResponse("Liste des sessions de pesée récupérée", HttpStatus.OK,
                    service.list(projetUniqueId, statut, page, size), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors de la récupération des sessions de pesée", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/sessions/{uniqueId}")
    public ResponseEntity<ApiResponse<SessionPeseeDTO>> detail(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Session de pesée récupérée", HttpStatus.OK, service.detail(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/evolution")
    public ResponseEntity<ApiResponse<List<EvolutionPoidsDTO>>> evolution(@RequestParam(required = false) String projetUniqueId) {
        try {
            return ApiResponse.createResponse("Évolution du poids récupérée", HttpStatus.OK, service.evolution(projetUniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
