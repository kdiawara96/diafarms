package com.diafarms.ml.controllers;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.services.ProjetServices;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/projets")
@RequiredArgsConstructor
public class ProjetsControllers {

    private final ProjetServices services;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<ProjetsDTO>>> getProjets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "tous") String filter) {
        
        // Récupération de la réponse paginée depuis le service
        PaginatedResponse<ProjetsDTO> response = services.getAllProjets(page, size, search, filter);

        // Encapsulation uniforme dans l'ApiResponse globale
        return ApiResponse.createResponse(
                "Liste des projets récupérée avec succès",
                HttpStatus.OK,
                response,
                null
        );
    }

    
}
