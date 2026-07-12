package com.diafarms.ml.services;

public interface PasswordResetService {

    /**
     * Génère un code à 6 chiffres (valable 5 minutes) et l'envoie par email.
     * Ne lève jamais d'erreur si l'email est inconnu (pour ne pas révéler
     * quels emails sont enregistrés) : ne fait simplement rien dans ce cas.
     */
    void requestReset(String email);

    /**
     * Vérifie que le code correspond et n'a pas expiré, sans le consommer
     * (l'utilisateur passe encore par l'écran de saisie du nouveau mot de passe).
     */
    boolean verifyCode(String email, String code);

    /**
     * Revalide le code puis change le mot de passe. Consomme le code (usage unique).
     */
    void resetPassword(String email, String code, String newPassword);
}
