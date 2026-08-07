package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.FarmAppSettingsDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.FarmAppSettingsService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/farm-settings")
@RequiredArgsConstructor
public class FarmAppSettingsController {

    private final FarmAppSettingsService service;

    @GetMapping
    public ResponseEntity<ApiResponse<FarmAppSettingsDTO>> get() {
        try {
            return ApiResponse.createResponse("Paramètres récupérés", HttpStatus.OK, service.getSettings(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping
    public ResponseEntity<ApiResponse<FarmAppSettingsDTO>> update(@RequestBody FarmAppSettingsDTO request) {
        try {
            return ApiResponse.createResponse("Paramètres mis à jour", HttpStatus.OK, service.updateSettings(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.FORBIDDEN, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
