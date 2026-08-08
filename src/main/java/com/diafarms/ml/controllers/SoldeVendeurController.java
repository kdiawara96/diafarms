package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.SoldeVendeurDTO;
import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.ServiceImpl.SoldeVendeurServiceImpl;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/soldes-vendeur")
@RequiredArgsConstructor
public class SoldeVendeurController {

    private final SoldeVendeurServiceImpl service;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    // Le sien — visible par tout vendeur pour la carte KPI de son tableau de bord.
    @GetMapping("/moi")
    public ResponseEntity<ApiResponse<SoldeVendeurDTO>> moi() {
        try {
            return ApiResponse.createResponse("Solde récupéré", HttpStatus.OK, service.getSolde(getCurrentUserSafe()), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Vue d'ensemble farm-wide — admin/responsable, pour repérer les dettes en cours.
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<SoldeVendeurDTO>>> list() {
        try {
            Utilisateurs currentUser = getCurrentUserSafe();
            List<SoldeVendeurDTO> result = currentUser != null
                    ? service.listNonZero(currentUser.getFarm())
                    : List.of();
            return ApiResponse.createResponse("Soldes vendeurs récupérés", HttpStatus.OK, result, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
