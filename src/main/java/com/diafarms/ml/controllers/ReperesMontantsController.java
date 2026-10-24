package com.diafarms.ml.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.ServiceImpl.ReperesMontantsService;
import com.diafarms.ml.others.ApiResponse;

import lombok.RequiredArgsConstructor;

// Prix habituels et plus gros montants récents de la ferme : le web s'en sert pour
// demander confirmation d'un montant très inhabituel (voir ReperesMontantsService).
@RestController
@RequestMapping("/diafarms/api/v1/reperes-montants")
@RequiredArgsConstructor
public class ReperesMontantsController {

    private final ReperesMontantsService service;

    @GetMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> reperes() {
        try {
            return ApiResponse.createResponse("Repères", HttpStatus.OK, service.reperes(), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ReperesMontantsController.class).error("Repères montants", e);
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
