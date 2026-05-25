package com.diafarms.ml.controllers;

import com.diafarms.ml.DTO.RaceDTO;
import com.diafarms.ml.models.Race;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.RaceServices;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/diafarms/api/v1/races")
@RequiredArgsConstructor
public class RaceController {
    private final RaceServices services;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<RaceDTO>> create(@RequestBody Race request) {
        try {
            RaceDTO result = services.create(request);
            return ApiResponse.createResponse("Race créée avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<RaceDTO>> update(@RequestBody Race request, @PathVariable("uniqueId") String uniqueId) {
        try {
            RaceDTO result = services.update(request);
            return ApiResponse.createResponse("Race mise à jour avec succès", HttpStatus.OK, result, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur de validation", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(@PathVariable("uniqueId") String uniqueId) {
        try {
            String result = services.deleteOrRecover(uniqueId);
            return ApiResponse.createResponse(result, HttpStatus.OK, result, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<RaceDTO>>> list() {
        try {
            List<RaceDTO> result = services.findAll();
            return ApiResponse.createResponse("Liste récupérée", HttpStatus.OK, result, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<RaceDTO>>> search(@RequestParam("keyword") String keyword) {
        try {
            List<RaceDTO> result = services.search(keyword);
            return ApiResponse.createResponse("Recherche réussie", HttpStatus.OK, result, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
