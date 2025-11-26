package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.models.Logs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/logs") 
@RequiredArgsConstructor
public class LogsController {
     private final LogsServices logsServices;

    /** Récupération de tous les logs */
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<Logs>>> getAll(
            @RequestParam int page,
            @RequestParam int size,
            @RequestParam(defaultValue = "folder") String type
    ) {
        PaginatedResponse<Logs> response = logsServices.getAll(page, size, type);

        return ApiResponse.createResponse(
                "Liste des logs récupérée",
                HttpStatus.OK,
                response,
                null
        );
    }

    /** Récupération des logs par idAction */
    @GetMapping("/list/by-action")
    public ResponseEntity<ApiResponse<PaginatedResponse<Logs>>> getAllByIdAction(
            @RequestParam Long idAction,
            @RequestParam int page,
            @RequestParam int size,
            @RequestParam(defaultValue = "folder") String type
    ) {
        PaginatedResponse<Logs> response = logsServices.getAllByIdAction(idAction, page, size, type);

        return ApiResponse.createResponse(
                "Logs par idAction récupérés",
                HttpStatus.OK,
                response,
                null
        );
    }

    /** Récupération des logs par nom de classe */
    @GetMapping("/list/by-class")
    public ResponseEntity<ApiResponse<PaginatedResponse<Logs>>> getAllByNomClass(
            @RequestParam String nomClass,
            @RequestParam int page,
            @RequestParam int size,
            @RequestParam(defaultValue = "folder") String type
    ) {
        PaginatedResponse<Logs> response = logsServices.getAllByNomClass(nomClass, page, size, type);

        return ApiResponse.createResponse(
                "Logs filtrés par nom de classe récupérés",
                HttpStatus.OK,
                response,
                null
        );
    }

    /** Recherche simple */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PaginatedResponse<Logs>>> search(
            @RequestParam("search") String search
    ) {
        PaginatedResponse<Logs> result = logsServices.search(search.trim());

        return ApiResponse.createResponse(
                "Résultat de recherche",
                HttpStatus.OK,
                result,
                null
        );
    }

    /** Soft delete ou restore */
    @DeleteMapping("/delete")
    public ResponseEntity<ApiResponse<String>> delete(
            @RequestParam("uniqueId") String uniqueId
    ) {
        try {
            String result = logsServices.delete(uniqueId);

            return ApiResponse.createResponse(
                    "Action effectuée",
                    HttpStatus.OK,
                    result,
                    null
            );
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(
                    "Erreur",
                    HttpStatus.NOT_FOUND,
                    null,
                    List.of(e.getMessage())
            );
        }
    }
}
