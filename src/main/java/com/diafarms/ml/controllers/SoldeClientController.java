package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.SoldeClientDTO;
import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.ServiceImpl.SoldeClientServiceImpl;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/soldes-client")
@RequiredArgsConstructor
public class SoldeClientController {

    private final SoldeClientServiceImpl service;
    private final OtherService otherService;

    private Utilisateurs getCurrentUserSafe() {
        try {
            return otherService.getCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    // Vue d'ensemble farm-wide — pour repérer les dettes client en cours (Clients,
    // Comptabilité, Reporting).
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<SoldeClientDTO>>> list() {
        try {
            Utilisateurs currentUser = getCurrentUserSafe();
            List<SoldeClientDTO> result = currentUser != null
                    ? service.listNonZero(currentUser.getFarm())
                    : List.of();
            return ApiResponse.createResponse("Soldes clients récupérés", HttpStatus.OK, result, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
