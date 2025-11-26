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

import com.diafarms.ml.DTO.UtilisateursDto;
import com.diafarms.ml.DTO.mappers.UtilisateurMapper;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.UserRequest;
import com.diafarms.ml.services.UtilisateursServices;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/users") 
@RequiredArgsConstructor
public class usersControllers {
    
    private UtilisateursServices services;
    private final UtilisateurMapper mapper;


      // =======================
    // 1) CREATE
    // =======================
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<UtilisateursDto>> createUser(
            @RequestBody UserRequest request
    ) {
        try {
            UtilisateursDto dto = services.save(request);
            return ApiResponse.createResponse(
                    "Utilisateur créé avec succès!",
                    HttpStatus.OK,
                    dto,
                    null
            );
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(
                    "Données invalides",
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


    // =======================
    // 2) UPDATE
    // =======================
    @PutMapping("/update/{user_uid}")
    public ResponseEntity<ApiResponse<UtilisateursDto>> updateUser(
            @PathVariable("user_uid") String userUid,
            @RequestBody UserRequest request
    ) {
        try {
            UtilisateursDto dto = services.update(userUid, request);
            return ApiResponse.createResponse(
                    "Utilisateur mis à jour avec succès!",
                    HttpStatus.OK,
                    dto,
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
                    "Utilisateur introuvable",
                    HttpStatus.NOT_FOUND,
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


    // =======================
    // 3) DELETE
    // =======================
    @DeleteMapping("/delete/{user_uid}")
    public ResponseEntity<ApiResponse<String>> deleteUser(
            @PathVariable("user_uid") String userUid
    ) {
        try {
            String message = services.delete(userUid);
            return ApiResponse.createResponse(
                    message,
                    HttpStatus.OK,
                    message,
                    null
            );

        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    "Utilisateur introuvable",
                    HttpStatus.NOT_FOUND,
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


    // =======================
    // 4) ARCHIVE
    // =======================
    @PutMapping("/archive/{user_uid}")
    public ResponseEntity<ApiResponse<String>> archiveUser(
            @PathVariable("user_uid") String userUid
    ) {
        try {
            String msg = services.archive(userUid);
            return ApiResponse.createResponse(
                    "Utilisateur archivé avec succès!",
                    HttpStatus.OK,
                    msg,
                    null
            );

        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    "Utilisateur introuvable",
                    HttpStatus.NOT_FOUND,
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


    // =======================
    // 5) READ (username, email, ou téléphone)
    // =======================
     @GetMapping("/read")
    public ResponseEntity<ApiResponse<UtilisateursDto>> readUser(@RequestParam String identifiant) {
        try {
            Utilisateurs user = services.readByUsernameOrEmailOrPhone(identifiant);
            UtilisateursDto dto = mapper.toDto(user);

            return ApiResponse.createResponse(
                    "Utilisateur trouvé",
                    HttpStatus.OK,
                    dto,
                    null
            );

        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Utilisateur introuvable",
                    HttpStatus.NOT_FOUND,
                    null,
                    List.of(e.getMessage())
            );
        }
    }


    // =======================
    // 6) FIND ALL (+ filtres)
    // =======================
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<PaginatedResponse<UtilisateursDto>>> getUsers(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(defaultValue = "") String type
    ) {
        try {
            PaginatedResponse<UtilisateursDto> data = services.findAll(page, size, type);

            return ApiResponse.createResponse(
                    "Liste récupérée",
                    HttpStatus.OK,
                    data,
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


    // =======================
    // 7) SEARCH simple (nom, username, email, phone)
    // =======================
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PaginatedResponse<UtilisateursDto>>> searchUser(
            @RequestParam("q") String search
    ) {
        try {
            PaginatedResponse<UtilisateursDto> data = services.search(search);

            return ApiResponse.createResponse(
                    "Résultats trouvés",
                    HttpStatus.OK,
                    data,
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
