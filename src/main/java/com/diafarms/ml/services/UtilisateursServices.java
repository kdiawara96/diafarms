package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.UtilisateursDTO;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.request.create.UserCreate;

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
    UtilisateursDTO getUtilisateurByUniqueId(String uniqueId);
    UtilisateursDTO createUtilisateur(UtilisateursDTO dto);
    UtilisateursDTO updateUtilisateur(String uniqueId, UtilisateursDTO dto);
    UtilisateursDTO regenerateQRCodeToken(String uniqueId);
}
