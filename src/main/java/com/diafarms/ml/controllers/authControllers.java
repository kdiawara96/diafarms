package com.diafarms.ml.controllers;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.AuthServices;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1") 
@RequiredArgsConstructor
public class authControllers {
    
    private final AuthServices serives;
    

  @PostMapping("/auth")
    public ResponseEntity<ApiResponse<Object>> auth(String grantType, String identifiant, String password, boolean ouiRefresh, String refreshToken){
        try {
            // Réponse réussie
            return ApiResponse.createResponse("Opération réussie", HttpStatus.OK, serives.jwt(grantType, identifiant, password, ouiRefresh, refreshToken), null);
        } catch ( NoSuchElementException e) {
            // Gestion des erreurs de validation
            List<String> errors = Arrays.asList(e.getMessage());
            return ApiResponse.createResponse("Erreur de validation", HttpStatus.BAD_REQUEST, null, errors);
        } catch ( IllegalArgumentException e) {
            // Gestion des erreurs de validation
            List<String> errors = Arrays.asList(e.getMessage());
            return ApiResponse.createResponse("La données est invalide ou manquante pour la sauvegarde", HttpStatus.BAD_REQUEST, null, errors);
        }  catch (Exception e) {
            // Gestion des autres exceptions
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }


    
}
