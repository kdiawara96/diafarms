package com.diafarms.ml.controllers;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.ServiceImpl.GuideDemarrageService;
import com.diafarms.ml.ServiceImpl.ParrainageService;
import com.diafarms.ml.ServiceImpl.ResumeHebdoService;
import com.diafarms.ml.others.ApiResponse;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Croissance (côté ferme) : guide « Bien démarrer », parrainage, réglage du résumé de la
// semaine. La vérification d'un code de parrainage est publique (page d'inscription, voir
// SecurityConfiguration.publicFilterChain) et ne répond que oui ou non.
@RestController
@RequiredArgsConstructor
@Slf4j
public class CroissanceController {

    private final GuideDemarrageService guide;
    private final ParrainageService parrainage;
    private final ResumeHebdoService resume;

    private <T> ResponseEntity<ApiResponse<T>> repondre(String message, Supplier<T> action) {
        try {
            return ApiResponse.createResponse(message, HttpStatus.OK, action.get(), null);
        } catch (AccessDeniedException e) {
            return ApiResponse.createResponse("Accès refusé", HttpStatus.FORBIDDEN, null, List.of(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            log.error("Croissance : {}", e.getMessage(), e);
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/diafarms/api/v1/croissance/guide")
    public ResponseEntity<ApiResponse<GuideDemarrageService.GuideDTO>> guide() {
        return repondre("Guide", guide::guide);
    }

    @PostMapping("/diafarms/api/v1/croissance/guide/masquer")
    public ResponseEntity<ApiResponse<GuideDemarrageService.GuideDTO>> masquerGuide() {
        return repondre("Guide fermé", guide::masquer);
    }

    @GetMapping("/diafarms/api/v1/croissance/parrainage")
    public ResponseEntity<ApiResponse<ParrainageService.MonParrainageDTO>> parrainage() {
        return repondre("Parrainage", parrainage::moi);
    }

    // {"code": "K7M2QX"} : la ferme a été invitée par une autre ferme.
    @PostMapping("/diafarms/api/v1/croissance/parrainage/code")
    public ResponseEntity<ApiResponse<ParrainageService.MonParrainageDTO>> saisirCode(@RequestBody(required = false) Map<String, String> body) {
        return repondre("Code de parrainage enregistré", () -> parrainage.saisirCode(body == null ? null : body.get("code")));
    }

    @GetMapping("/diafarms/api/v1/croissance/resume-hebdo")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> resumeHebdo() {
        return repondre("Résumé de la semaine", () -> Map.of("actif", resume.reglage()));
    }

    // {"actif": false} coupe le résumé du lundi, {"actif": true} le remet.
    @PutMapping("/diafarms/api/v1/croissance/resume-hebdo")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> changerResumeHebdo(@RequestBody(required = false) Map<String, Boolean> body) {
        return repondre("Réglage enregistré", () -> {
            if (body == null || body.get("actif") == null) throw new IllegalArgumentException("Indiquez actif : true ou false.");
            return Map.of("actif", resume.changerReglage(body.get("actif")));
        });
    }

    // Public : le code existe-t-il ? (jamais le nom de la ferme)
    @GetMapping("/diafarms/api/v1/parrainage/verifier")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> verifier(@RequestParam(required = false) String code) {
        return repondre("Code vérifié", () -> Map.of("valide", parrainage.codeValide(code)));
    }
}
