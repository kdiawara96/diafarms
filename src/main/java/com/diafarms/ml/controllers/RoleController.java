package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import com.diafarms.ml.DTO.RoleDto;
import com.diafarms.ml.DTO.mappers.RoleMapper;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.selectClass.RolesSelect;
import com.diafarms.ml.services.RolesServices;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/users") 
@RequiredArgsConstructor
public class RoleController {

    private final RolesServices services;

    // --------------------------------------------------------
    // 🔵 CREATE
    // --------------------------------------------------------
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<RoleDto>> create(@RequestBody Roles request) {
        try {
            RoleDto result = services.create(request);

            return ApiResponse.createResponse(
                    "Rôle créé avec succès",
                    HttpStatus.OK,
                    result,
                    null
            );

        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(
                    "Données invalides",
                    HttpStatus.BAD_REQUEST,
                    null,
                    List.of(e.getMessage())
            );
        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    "Erreur",
                    HttpStatus.BAD_REQUEST,
                    null,
                    List.of(e.getMessage())
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur interne du serveur",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null,
                    null
            );
        }
    }

    // --------------------------------------------------------
    // 🟦 UPDATE
    // --------------------------------------------------------
    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<RoleDto>> update(
            @RequestBody Roles request,
            @PathVariable("uniqueId") String uniqueId
    ) {
        try {
            RoleDto result = services.update(request, uniqueId);

            return ApiResponse.createResponse(
                    "Rôle mis à jour avec succès",
                    HttpStatus.OK,
                    result,
                    null
            );

        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    "Erreur de validation",
                    HttpStatus.BAD_REQUEST,
                    null,
                    List.of(e.getMessage())
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur interne du serveur",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null,
                    null
            );
        }
    }

    // --------------------------------------------------------
    // 🗑 DELETE / RECOVER
    // --------------------------------------------------------
    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(
            @PathVariable("uniqueId") String uniqueId
    ) {
        try {
            String result = services.deleteOrRecover(uniqueId);

            return ApiResponse.createResponse(
                    result,
                    HttpStatus.OK,
                    result,
                    null
            );
        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    "Erreur",
                    HttpStatus.BAD_REQUEST,
                    null,
                    List.of(e.getMessage())
            );
        }
    }

    // --------------------------------------------------------
    // 🟪 ARCHIVE / UNARCHIVE
    // --------------------------------------------------------
    @PutMapping("/archive/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> archive(
            @PathVariable("uniqueId") String uniqueId
    ) {
        try {
            String result = services.archive(uniqueId);

            return ApiResponse.createResponse(
                    result,
                    HttpStatus.OK,
                    result,
                    null
            );

        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    "Erreur",
                    HttpStatus.BAD_REQUEST,
                    null,
                    List.of(e.getMessage())
            );
        }
    }

    // --------------------------------------------------------
    // 📄 LIST with pagination
    // --------------------------------------------------------
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<RoleDto>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "folder") String type
    ) {
        try {
            PaginatedResponse<RoleDto> result = services.findAll(page, size, type);

            return ApiResponse.createResponse(
                    "Liste récupérée",
                    HttpStatus.OK,
                    result,
                    null
            );

        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur interne du serveur",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null,
                    null
            );
        }
    }

    // --------------------------------------------------------
    // 🔍 SEARCH (sans pagination)
    // --------------------------------------------------------
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PaginatedResponse<RoleDto>>> search(
            @RequestParam("keyword") String keyword
    ) {
        try {
            PaginatedResponse<RoleDto> result = services.search(keyword);

            return ApiResponse.createResponse(
                    "Recherche réussie",
                    HttpStatus.OK,
                    result,
                    null
            );

        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur interne du serveur",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null,
                    null
            );
        }
    }

    // --------------------------------------------------------
    // 🟩 ROLE SELECT (pour les forms)
    // --------------------------------------------------------
    @GetMapping("/select")
    public ResponseEntity<ApiResponse<List<RolesSelect>>> roleSelect() {
        try {
            List<RolesSelect> result = services.roleSelect();

            return ApiResponse.createResponse(
                    "Liste des rôles",
                    HttpStatus.OK,
                    result,
                    null
            );

        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur interne du serveur",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null,
                    null
            );
        }
    }
}
