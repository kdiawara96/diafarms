package com.diafarms.ml.controllers;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import com.diafarms.ml.DTO.UtilisateursDto;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.UserRequest;
import com.diafarms.ml.services.UtilisateursServices;
import lombok.RequiredArgsConstructor;

/**
 * REST controller for user creation operations.
 */
@RestController
@RequestMapping("/diafarms/api/v1/users")
@RequiredArgsConstructor
public class usersControllers {

    private final UtilisateursServices services;

    /**
     * Creates a new user with auto-generated username and password.
     *
     * @param request the user creation request
     * @return response with created user data
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<UtilisateursDto>> createUser(@RequestBody UserRequest request) {
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
}
