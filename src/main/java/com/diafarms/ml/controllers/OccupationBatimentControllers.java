package com.diafarms.ml.controllers;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.models.OccupationBatiment;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.OccupationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/occupations-batiments") 
@RequiredArgsConstructor
public class OccupationBatimentControllers {

        private final OccupationService services;


    /**
     * Lie un projet à un bâtiment (Crée une occupation)
     * POST -> /diafarms/api/v1/occupations-batiments/assigner
     */
    @PostMapping("/assigner")
    public ResponseEntity<ApiResponse<OccupationBatiment>> assignerBatimentAProjet(
            @RequestParam Long projetId,
            @RequestParam Long batimentId,
            @RequestParam Integer nbSujets,
            @RequestParam(required = false) String dateEntree
    ) {
        try {
            OccupationBatiment response = services.assignerBatimentAProjet(projetId, batimentId, nbSujets, dateEntree);
            return ApiResponse.createResponse("Bâtiment assigné avec succès", HttpStatus.CREATED, response, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur lors de l'assignation", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        }
    }

    /**
     * Modifie une liaison existante (ex: changer de bâtiment ou ajuster les dates/sujets)
     * PUT -> /diafarms/api/v1/occupations-batiments/modifier
     */
    @PutMapping("/modifier")
    public ResponseEntity<ApiResponse<OccupationBatiment>> modifierOccupation(
            @RequestParam Long occupationId,
            @RequestParam Long nouveauBatimentId,
            @RequestParam Integer nouveauNbSujets,
            @RequestParam(required = false) String dateEntree,
            @RequestParam(required = false) String dateSortie
    ) {
        try {
            OccupationBatiment response = services.modifierOccupation(
                    occupationId, nouveauBatimentId, nouveauNbSujets, dateEntree, dateSortie
            );
            return ApiResponse.createResponse("Occupation modifiée avec succès", HttpStatus.OK, response, null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur lors de la modification", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        }
    }

    /**
     * Libère le bâtiment (Clôture l'occupation)
     * PATCH -> /diafarms/api/v1/occupations-batiments/liberer
     * Note : On utilise PATCH ou PUT ici car on ne supprime pas la ligne en BDD (on met à jour un statut et une date)
     */
    @PatchMapping("/liberer")
    public ResponseEntity<ApiResponse<String>> libererBatiment(
            @RequestParam Long occupationId,
            @RequestParam(required = false) String dateSortie
    ) {
        try {
            services.libererBatiment(occupationId, dateSortie);
            return ApiResponse.createResponse("Bâtiment libéré avec succès", HttpStatus.OK, "Bâtiment disponible.", null);
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur lors de la libération", HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        }
    }    
    
}
