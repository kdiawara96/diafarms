package com.diafarms.ml.services;

public interface EmailService {

    /**
     * Envoie l'email de bienvenue contenant les identifiants générés à la
     * création d'un compte. Retourne false (sans lever d'exception) en cas
     * d'échec d'envoi, pour permettre à l'appelant de proposer un repli
     * (afficher le mot de passe à l'écran) plutôt que d'échouer la création.
     */
    boolean sendWelcomeEmail(String to, String fullName, String username, String password);
}
