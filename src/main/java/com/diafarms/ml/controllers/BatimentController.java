package com.diafarms.ml.controllers;

import com.diafarms.ml.DTO.BatimentsDTO;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.BatimentServices;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/diafarms/api/v1/batiments")
@RequiredArgsConstructor
public class BatimentController {

    private final BatimentServices services;

    // ==================== CREATE ====================
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<BatimentsDTO>> create(@RequestBody Batiment request) {
        try {
            // Sécurité : s'assurer que l'ID est null pour une création
            request.setId(null);
            // Le uniqueId est généré côté service, on nettoie au cas où
            request.setUniqueId(null);
            
            BatimentsDTO result = services.create(request);
            return ApiResponse.createResponse(
                "Bâtiment créé avec succès", 
                HttpStatus.CREATED,  // 201 plus approprié pour création
                result, 
                null
            );
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(
                "Données invalides", 
                HttpStatus.BAD_REQUEST, 
                null, 
                List.of(e.getMessage())
            );
        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                "Erreur de création", 
                HttpStatus.BAD_REQUEST, 
                null, 
                List.of(e.getMessage())
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                "Erreur interne du serveur", 
                HttpStatus.INTERNAL_SERVER_ERROR, 
                null, 
                List.of("Une erreur inattendue s'est produite")
            );
        }
    }

    // ==================== READ (par ID) ====================
    // @GetMapping("/{uniqueId}")
    // public ResponseEntity<ApiResponse<BatimentsDTO>> getByUniqueId(
    //         @PathVariable("uniqueId") String uniqueId) {
    //     try {
    //         // Nécessite d'ajouter cette méthode dans le service/repo
    //         BatimentsDTO result = services.findByUniqueId(uniqueId);
    //         return ApiResponse.createResponse(
    //             "Bâtiment récupéré", 
    //             HttpStatus.OK, 
    //             result, 
    //             null
    //         );
    //     } catch (RuntimeException e) {
    //         return ApiResponse.createResponse(
    //             "Bâtiment non trouvé", 
    //             HttpStatus.NOT_FOUND, 
    //             null, 
    //             List.of(e.getMessage())
    //         );
    //     } catch (Exception e) {
    //         return ApiResponse.createResponse(
    //             "Erreur interne du serveur", 
    //             HttpStatus.INTERNAL_SERVER_ERROR, 
    //             null, 
    //             List.of("Une erreur inattendue s'est produite")
    //         );
    //     }
    // }

    // ==================== UPDATE ====================
    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<BatimentsDTO>> update(
            @RequestBody Batiment request, 
            @PathVariable("uniqueId") String uniqueId) {
        try {
            // CRITIQUE : injecter le uniqueId du PathVariable dans l'objet
            request.setUniqueId(uniqueId);
            
            BatimentsDTO result = services.update(request);
            return ApiResponse.createResponse(
                "Bâtiment mis à jour avec succès", 
                HttpStatus.OK, 
                result, 
                null
            );
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(
                "Données invalides", 
                HttpStatus.BAD_REQUEST, 
                null, 
                List.of(e.getMessage())
            );
        } catch (RuntimeException e) {
            // Distinguer 404 vs 400
            if (e.getMessage().contains("non trouvé")) {
                return ApiResponse.createResponse(
                    "Bâtiment non trouvé", 
                    HttpStatus.NOT_FOUND, 
                    null, 
                    List.of(e.getMessage())
                );
            }
            return ApiResponse.createResponse(
                "Erreur de validation", 
                HttpStatus.BAD_REQUEST, 
                null, 
                List.of(e.getMessage())
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                "Erreur interne du serveur", 
                HttpStatus.INTERNAL_SERVER_ERROR, 
                null, 
                List.of("Une erreur inattendue s'est produite")
            );
        }
    }

    // ==================== DELETE / RECOVER ====================
    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(
            @PathVariable("uniqueId") String uniqueId) {
        try {
            String result = services.deleteOrRecover(uniqueId);
            return ApiResponse.createResponse(
                result, 
                HttpStatus.OK, 
                result, 
                null
            );
        } catch (RuntimeException e) {
            if (e.getMessage().contains("non trouvé")) {
                return ApiResponse.createResponse(
                    "Bâtiment non trouvé", 
                    HttpStatus.NOT_FOUND, 
                    null, 
                    List.of(e.getMessage())
                );
            }
            return ApiResponse.createResponse(
                "Erreur", 
                HttpStatus.BAD_REQUEST, 
                null, 
                List.of(e.getMessage())
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                "Erreur interne du serveur", 
                HttpStatus.INTERNAL_SERVER_ERROR, 
                null, 
                List.of("Une erreur inattendue s'est produite")
            );
        }
    }

    // ==================== LIST (tous) ====================
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<BatimentsDTO>>> list() {
        try {
            List<BatimentsDTO> result = services.findAll();
            return ApiResponse.createResponse(
                "Liste récupérée", 
                HttpStatus.OK, 
                result, 
                null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                "Erreur interne du serveur", 
                HttpStatus.INTERNAL_SERVER_ERROR, 
                null, 
                List.of("Une erreur inattendue s'est produite")
            );
        }
    }


    @GetMapping("/select")
    public ResponseEntity<ApiResponse<List<BatimentsDTO>>> select() {
        try {
            List<BatimentsDTO> result = services.select();
            return ApiResponse.createResponse(
                "Liste récupérée", 
                HttpStatus.OK, 
                result, 
                null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                "Erreur interne du serveur", 
                HttpStatus.INTERNAL_SERVER_ERROR, 
                null, 
                List.of("Une erreur inattendue s'est produite")
            );
        }
    }
    // ==================== SEARCH ====================
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<BatimentsDTO>>> search(
            @RequestParam(value = "keyword", required = false) String keyword) {
        try {
            // Si keyword vide/null, retourner tous les actifs
            List<BatimentsDTO> result = services.search(keyword);
            return ApiResponse.createResponse(
                "Recherche réussie", 
                HttpStatus.OK, 
                result, 
                null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                "Erreur interne du serveur", 
                HttpStatus.INTERNAL_SERVER_ERROR, 
                null, 
                List.of("Une erreur inattendue s'est produite")
            );
        }
    }
}