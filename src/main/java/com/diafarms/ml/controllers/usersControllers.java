package com.diafarms.ml.controllers;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

import com.diafarms.ml.DTO.UtilisateursDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.UserCreate;
import com.diafarms.ml.request.update.UserUpdate;
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
    public ResponseEntity<ApiResponse<UtilisateursDTO>> createUser(@RequestBody UserCreate request) {
        try {
            UtilisateursDTO dto = services.save(request);
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

    @GetMapping("/select")
    public ResponseEntity<ApiResponse<List<UtilisateursDTO>>> select() {
        try {
            List<UtilisateursDTO> result = services.select();
            return ApiResponse.createResponse("Liste récupérée", HttpStatus.OK, result, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/select/producteurs")
    public ResponseEntity<ApiResponse<List<UtilisateursDTO>>> selectProducteurs() {
        try {
            List<UtilisateursDTO> result = services.selectProducteurs();
            return ApiResponse.createResponse("Liste des producteurs récupérée", HttpStatus.OK, result, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/select/financiers")
    public ResponseEntity<ApiResponse<List<UtilisateursDTO>>> selectFinanciers() {
        try {
            List<UtilisateursDTO> result = services.selectFinanciers();
            return ApiResponse.createResponse("Liste des financiers récupérée", HttpStatus.OK, result, null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    /**
     * Récupère la liste complète de tous les utilisateurs (pour le tableau principal).
     */
    @GetMapping("/all")
    public ResponseEntity<ApiResponse<List<UtilisateursDTO>>> getAllUsers() {
        try {
            List<UtilisateursDTO> result = services.getAllUtilisateurs();
            return ApiResponse.createResponse(
                    "Liste des utilisateurs récupérée avec succès", 
                    HttpStatus.OK, 
                    result, 
                    null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur lors de la récupération des utilisateurs", 
                    HttpStatus.INTERNAL_SERVER_ERROR, 
                    null, 
                    null
            );
        }
    }

    /**
     * Récupère les détails complets d'un utilisateur par son identifiant unique.
     */
    @GetMapping("/detail/{uniqueId}")
    public ResponseEntity<ApiResponse<UtilisateursDTO>> getUserByUniqueId(@PathVariable String uniqueId) {
        try {
            UtilisateursDTO dto = services.getUtilisateurByUniqueId(uniqueId);
            return ApiResponse.createResponse(
                    "Détails de l'utilisateur récupérés", 
                    HttpStatus.OK, 
                    dto, 
                    null
            );
        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    e.getMessage(), 
                    HttpStatus.NOT_FOUND, 
                    null, 
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

    /**
     * Modifie les informations d'un utilisateur (fullName, telephone, email, city, region, roles).
     */
    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<UtilisateursDTO>> updateUser(
            @PathVariable String uniqueId, 
            @RequestBody UserUpdate request) {
        try {
            UtilisateursDTO dto = services.updateUtilisateur(uniqueId, request);
            return ApiResponse.createResponse(
                    "Utilisateur modifié avec succès!", 
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
                    e.getMessage(), 
                    HttpStatus.NOT_FOUND, 
                    null, 
                    null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur lors de la modification de l'utilisateur", 
                    HttpStatus.INTERNAL_SERVER_ERROR, 
                    null, 
                    null
            );
        }
    }

    /**
     * Génère un nouvel identifiant unique pour révoquer l'ancien QR code et forcer la mise à jour mobile.
     */
    @PostMapping("/regenerate-qr/{uniqueId}")
    public ResponseEntity<ApiResponse<UtilisateursDTO>> regenerateQR(@PathVariable String uniqueId) {
        try {
            UtilisateursDTO dto = services.regenerateQRCodeToken(uniqueId);
            return ApiResponse.createResponse(
                    "Nouveau QR Code généré avec succès!", 
                    HttpStatus.OK, 
                    dto, 
                    null
            );
        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    e.getMessage(), 
                    HttpStatus.NOT_FOUND, 
                    null, 
                    null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur lors de la régénération du QR Code", 
                    HttpStatus.INTERNAL_SERVER_ERROR, 
                    null, 
                    null
            );
        }
    }

    /**
     * Révoque et désactive un utilisateur par son identifiant unique.
     */
    @PutMapping("/revoke/{uniqueId}")
    public ResponseEntity<ApiResponse<UtilisateursDTO>> revokeUser(@PathVariable String uniqueId) {
        try {
            UtilisateursDTO dto = services.revoquerUtilisateur(uniqueId);
            return ApiResponse.createResponse(
                    "Utilisateur révoqué et accès coupés avec succès!", 
                    HttpStatus.OK, 
                    dto, 
                    null
            );
        } catch (RuntimeException e) {
            return ApiResponse.createResponse(
                    e.getMessage(), 
                    HttpStatus.NOT_FOUND, 
                    null, 
                    null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur lors de la révocation de l'utilisateur", 
                    HttpStatus.INTERNAL_SERVER_ERROR, 
                    null, 
                    null
            );
        }
    }

    /**
     * Crée un nouvel utilisateur lié à la ferme de l'administrateur connecté,
     * avec génération automatique d'un identifiant unique (QR Code) et mot de passe.
     *
     * @param request les données de création simplifiées (fullName, telephone, etc.)
     * @return response avec l'utilisateur créé et le mot de passe temporaire en clair
     */
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<UtilisateursDTO>> createUserProOrFinance(@RequestBody UserCreate request) {
        try {
            // Validation de base des champs obligatoires côté contrôleur
            if (request.getFullName() == null || request.getFullName().trim().isEmpty()) {
                return ApiResponse.createResponse("Le nom complet est obligatoire", HttpStatus.BAD_REQUEST, null, null);
            }
            if (request.getTelephone() == null || request.getTelephone().trim().isEmpty()) {
                return ApiResponse.createResponse("Le numéro de téléphone est obligatoire", HttpStatus.BAD_REQUEST, null, null);
            }

            UtilisateursDTO dto = services.createUtilisateurProdOrFinan(request);
            
            return ApiResponse.createResponse(
                    "Utilisateur créé avec succès !",
                    HttpStatus.CREATED,
                    dto,
                    null
            );
        } catch (RuntimeException e) {
            // Attrape les erreurs métier (ex: doublon de téléphone dans la ferme)
            return ApiResponse.createResponse(
                    e.getMessage(),
                    HttpStatus.BAD_REQUEST,
                    null,
                    null
            );
        } catch (Exception e) {
            return ApiResponse.createResponse(
                    "Erreur interne du serveur lors de la création",
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    null,
                    null
            );
        }
    }
}
