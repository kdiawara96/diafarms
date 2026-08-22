package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.RapportJournalierDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.RapportJournalierService;

import lombok.RequiredArgsConstructor;

// Voir RapportJournalierServiceImpl — rapport jour par jour (production + finance)
// reconstitué à partir des données déjà en base pour UN projet précis.
@RestController
@RequestMapping("/diafarms/api/v1/rapports")
@RequiredArgsConstructor
public class RapportJournalierControllers {

    private final RapportJournalierService rapportJournalierService;

    @GetMapping("/journalier/{projetUniqueId}")
    public ResponseEntity<ApiResponse<RapportJournalierDTO>> getRapportJournalier(@PathVariable String projetUniqueId) {
        try {
            RapportJournalierDTO result = rapportJournalierService.genererPourProjet(projetUniqueId);
            return ApiResponse.createResponse("Rapport journalier généré avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
