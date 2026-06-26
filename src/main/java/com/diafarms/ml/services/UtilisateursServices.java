package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.UtilisateursDTO;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.UserCreate;
import com.diafarms.ml.request.update.UserUpdate;

/**
 * Service interface for user management operations.
 */
public interface UtilisateursServices {

    /**
     * Creates a new user with auto-generated username and password.
     *
     * @param data the user request data
     * @return the created user DTO
     */
    UtilisateursDTO save(UserCreate data);

    /**
     * Finds a user by username or email for authentication.
     *
     * @param usernameOrEmail the username or email
     * @return the user entity
     */
    Utilisateurs readByUsernameOrEmail(String usernameOrEmail);

    List<UtilisateursDTO> select();
    List<UtilisateursDTO> selectProducteurs();
    List<UtilisateursDTO> selectFinanciers();
    List<UtilisateursDTO> getAllUtilisateurs();
    PaginatedResponse<UtilisateursDTO> getAllUtilisateurs(String searchTerm, int page, int size);

    UtilisateursDTO getUtilisateurByUniqueId(String uniqueId);
    UtilisateursDTO createUtilisateurProdOrFinan(UserCreate dto);
    UtilisateursDTO updateUtilisateur(String uniqueId, UserUpdate dto);
    UtilisateursDTO regenerateQRCodeToken(String uniqueId);
    UtilisateursDTO revoquerUtilisateur(String uniqueId);
}
