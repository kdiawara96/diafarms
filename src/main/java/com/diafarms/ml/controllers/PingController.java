package com.diafarms.ml.controllers;

import com.diafarms.ml.others.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Endpoint public de test de connectivité (écran Diagnostics mobile). Le chemin
 * /diafarms/api/v1/test est déjà déclaré dans la chaîne publique de
 * SecurityConfiguration mais n'avait jamais été implémenté.
 */
@RestController
@RequestMapping("/diafarms/api/v1")
public class PingController {

    @GetMapping("/test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> ping() {
        return ApiResponse.createResponse(
                "Serveur Diafarms joignable",
                HttpStatus.OK,
                Map.of("time", LocalDateTime.now().toString()),
                null
        );
    }
}
