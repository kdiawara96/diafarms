package com.diafarms.ml.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.DeviseFermeDTO;
import com.diafarms.ml.DTO.FarmAppSettingsDTO;
import com.diafarms.ml.DTO.ModePaiementFermeDTO;
import com.diafarms.ml.ServiceImpl.ModesPaiementService;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.FarmAppSettingsService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/farm-settings")
@RequiredArgsConstructor
public class FarmAppSettingsController {

    private final FarmAppSettingsService service;
    private final ModesPaiementService modesService;

    @GetMapping
    public ResponseEntity<ApiResponse<FarmAppSettingsDTO>> get() {
        try {
            return ApiResponse.createResponse("Paramètres récupérés", HttpStatus.OK, service.getSettings(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping
    public ResponseEntity<ApiResponse<FarmAppSettingsDTO>> update(@RequestBody FarmAppSettingsDTO request) {
        try {
            return ApiResponse.createResponse("Paramètres mis à jour", HttpStatus.OK, service.updateSettings(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.FORBIDDEN, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // ---------- Pays, devise et modes de paiement (voir ModesPaiementService) ----------
    // Lecture : tout utilisateur de la ferme (web et téléphone en ont besoin pour
    // afficher les montants et proposer les modes). Modification : ADMIN (propriétaire).

    @GetMapping("/devise")
    public ResponseEntity<ApiResponse<DeviseFermeDTO>> devise() {
        try {
            return ApiResponse.createResponse("Devise de la ferme", HttpStatus.OK, modesService.deviseCourante(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/devise")
    public ResponseEntity<ApiResponse<DeviseFermeDTO>> changerDevise(@RequestBody Map<String, String> body) {
        try {
            return ApiResponse.createResponse("Pays et devise mis à jour", HttpStatus.OK,
                    modesService.changerDevise(body.get("pays"), body.get("devise")), null);
        } catch (IllegalArgumentException e) {
            HttpStatus st = e.getMessage() != null && e.getMessage().startsWith("Seul") ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
            return ApiResponse.createResponse(e.getMessage(), st, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/catalogue-pays")
    public ResponseEntity<ApiResponse<Map<String, Object>>> cataloguePays() {
        return ApiResponse.createResponse("Pays, devises et modes proposés", HttpStatus.OK, ModesPaiementService.catalogue(), null);
    }

    /** Modes ACTIFS de la ferme, dans l'ordre choisi : la liste à proposer dans tout
     * formulaire de paiement (web et téléphone). */
    @GetMapping("/modes-paiement")
    public ResponseEntity<ApiResponse<List<ModePaiementFermeDTO>>> modesPaiement() {
        try {
            return ApiResponse.createResponse("Modes de paiement", HttpStatus.OK, modesService.actifsCourants(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    /** Configuration complète (cochés et décochés), pour Paramètres. */
    @GetMapping("/modes-paiement/configuration")
    public ResponseEntity<ApiResponse<List<ModePaiementFermeDTO>>> modesPaiementConfiguration() {
        try {
            return ApiResponse.createResponse("Modes de paiement", HttpStatus.OK, modesService.configurationCourante(), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/modes-paiement")
    public ResponseEntity<ApiResponse<List<ModePaiementFermeDTO>>> configurerModes(@RequestBody List<ModePaiementFermeDTO> body) {
        try {
            return ApiResponse.createResponse("Modes de paiement mis à jour", HttpStatus.OK, modesService.configurer(body), null);
        } catch (IllegalArgumentException e) {
            HttpStatus st = e.getMessage() != null && e.getMessage().startsWith("Seul") ? HttpStatus.FORBIDDEN : HttpStatus.BAD_REQUEST;
            return ApiResponse.createResponse(e.getMessage(), st, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
