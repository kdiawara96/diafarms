package com.diafarms.ml.services;

public interface EmailService {

    /**
     * Envoie l'email de bienvenue contenant les identifiants générés à la
     * création d'un compte. Retourne false (sans lever d'exception) en cas
     * d'échec d'envoi, pour permettre à l'appelant de proposer un repli
     * (afficher le mot de passe à l'écran) plutôt que d'échouer la création.
     */
    boolean sendWelcomeEmail(String to, String fullName, String username, String password);

    /**
     * Envoie le code de vérification (6 chiffres, valable 5 minutes) pour la
     * réinitialisation de mot de passe.
     */
    boolean sendPasswordResetCode(String to, String fullName, String code);

    /**
     * Envoie un nouveau mot de passe généré par un ADMIN (bouton "Réinitialiser" sur
     * la fiche utilisateur) — distinct de sendPasswordResetCode (code à saisir soi-même
     * via /forgot-password) : ici le mot de passe est déjà changé côté serveur, l'email
     * ne fait que le communiquer.
     */
    boolean sendPasswordResetByAdmin(String to, String fullName, String username, String newPassword);
}
