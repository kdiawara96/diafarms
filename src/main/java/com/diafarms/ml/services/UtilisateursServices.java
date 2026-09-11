package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.UtilisateursDTO;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.UserCreate;
import com.diafarms.ml.request.update.UpdatePassResquest;
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
    List<UtilisateursDTO> selectResponsables();
    List<UtilisateursDTO> selectVentes();
    List<UtilisateursDTO> getAllUtilisateurs();
    PaginatedResponse<UtilisateursDTO> getAllUtilisateurs(String searchTerm, int page, int size);

    UtilisateursDTO getUtilisateurByUniqueId(String uniqueId);
    UtilisateursDTO createUtilisateurProdOrFinan(UserCreate dto);
    UtilisateursDTO updateUtilisateur(String uniqueId, UserUpdate dto);
    UtilisateursDTO regenerateQRCodeToken(String uniqueId);
    UtilisateursDTO revoquerUtilisateur(String uniqueId);
    // Supprime réellement si le compte n'a jamais rien créé dans le système (aucune
    // donnée liée), sinon archive (corbeille) — voir UtilisateurImpl pour le détail
    // du mécanisme (tentative isolée, repli automatique).
    String supprimerOuArchiverUtilisateur(String uniqueId);
    UtilisateursDTO restaurerUtilisateur(String uniqueId);
    PaginatedResponse<UtilisateursDTO> getCorbeille(int page, int size);
    UtilisateursDTO changePassword(String uniqueId, UpdatePassResquest data);

    /** Réservé à un ADMIN/SUPER_ADMIN : génère et envoie par email un nouveau mot de
     * passe, jamais retourné en clair dans la réponse. */
    UtilisateursDTO resetPasswordAndNotify(String uniqueId);

    /** Invalide immédiatement tous les tokens web (mot de passe) déjà émis pour ce
     * compte — appelé à la déconnexion (voir authControllers.logout). N'affecte
     * jamais les tokens QR mobile, voir Utilisateurs.tokenVersion. */
    void revoquerSessionsWeb(String uniqueId);
}
