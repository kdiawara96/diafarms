package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.LogsDTO;
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
    public ResponseEntity<ApiResponse<PaginatedResponse<LogsDTO>>> getAll(
            @RequestParam int page,
            @RequestParam int size,
            @RequestParam(required = false) String search
    ) {
        PaginatedResponse<LogsDTO> response = logsServices.getAll(page, size, search);

        return ApiResponse.createResponse(
                "Liste des logs récupérée",
                HttpStatus.OK,
                response,
                null
        );
    }

    /** Récupération des logs par idAction */
    @GetMapping("/list/by-action")
    public ResponseEntity<ApiResponse<PaginatedResponse<LogsDTO>>> getAllByIdAction(
            @RequestParam Long idAction,
            @RequestParam int page,
            @RequestParam int size
    ) {
        PaginatedResponse<LogsDTO> response = logsServices.getAllByIdAction(idAction, page, size);

        return ApiResponse.createResponse(
                "Logs par idAction récupérés",
                HttpStatus.OK,
                response,
                null
        );
    }

    /** Récupération des logs par nom de classe */
    @GetMapping("/list/by-class")
    public ResponseEntity<ApiResponse<PaginatedResponse<LogsDTO>>> getAllByNomClass(
            @RequestParam String nomClass,
            @RequestParam int page,
            @RequestParam int size
    ) {
        PaginatedResponse<LogsDTO> response = logsServices.getAllByNomClass(nomClass, page, size);

        return ApiResponse.createResponse(
                "Logs filtrés par nom de classe récupérés",
                HttpStatus.OK,
                response,
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
        } catch (org.springframework.security.access.AccessDeniedException e) {
            return ApiResponse.createResponse("Accès refusé", HttpStatus.FORBIDDEN, null, List.of(e.getMessage()));
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
