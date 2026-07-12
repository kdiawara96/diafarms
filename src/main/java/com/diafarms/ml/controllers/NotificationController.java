package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.NotificationDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.services.NotificationService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService service;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<NotificationDTO>>> list() {
        try {
            return ApiResponse.createResponse("Notifications récupérées", HttpStatus.OK, service.getActiveNotifications(), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/projet/{projetUniqueId}")
    public ResponseEntity<ApiResponse<List<NotificationDTO>>> listForProjet(@PathVariable String projetUniqueId) {
        try {
            return ApiResponse.createResponse("Alertes du projet récupérées", HttpStatus.OK, service.getActiveNotificationsForProjet(projetUniqueId), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/{key}/read")
    public ResponseEntity<ApiResponse<String>> markRead(@PathVariable String key) {
        try {
            service.markRead(key);
            return ApiResponse.createResponse("Notification marquée comme lue", HttpStatus.OK, "OK", null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/read-all")
    public ResponseEntity<ApiResponse<String>> markAllRead() {
        try {
            service.markAllRead();
            return ApiResponse.createResponse("Toutes les notifications ont été marquées comme lues", HttpStatus.OK, "OK", null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
