package com.diafarms.ml.controllers;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.ReportingDTO;
import com.diafarms.ml.ServiceImpl.ReportingService;
import com.diafarms.ml.others.ApiResponse;

import lombok.RequiredArgsConstructor;

// Page Reporting du web : argent, élevage, séries et détail par Projet sur une période,
// tout calculé côté serveur (voir ReportingService). Dates "yyyy-MM-dd" ; sans date :
// du 1er du mois à aujourd'hui.
@RestController
@RequestMapping("/diafarms/api/v1/reporting")
@RequiredArgsConstructor
public class ReportingController {

    private final ReportingService service;

    @GetMapping
    public ResponseEntity<ApiResponse<ReportingDTO>> rapport(
            @RequestParam(required = false) String dateDebut,
            @RequestParam(required = false) String dateFin,
            @RequestParam(required = false) String projetUniqueId) {
        try {
            return ApiResponse.createResponse("Reporting", HttpStatus.OK,
                    service.rapport(date(dateDebut, "dateDebut"), date(dateFin, "dateFin"), projetUniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ReportingController.class).error("Reporting", e);
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    private static LocalDate date(String raw, String nom) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(nom + " invalide (attendu AAAA-MM-JJ) : " + raw);
        }
    }
}
