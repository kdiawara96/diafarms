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

    boolean sendAbonnementAValider(String to, String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference);

    boolean sendAbonnementValide(String to, String fullName, String farmNom,
            java.time.LocalDate dateFin);

    /**
     * Rappel de fin d'abonnement (J-7, J-1, début du délai de grâce), voir
     * AbonnementRappelService. message = texte brut, paragraphes séparés par une ligne
     * vide. Ne lève jamais d'exception : false si l'envoi échoue.
     */
    boolean sendRappelAbonnement(String to, String fullName, String sujet, String message);

    /**
     * E-mail simple signé « L'équipe Cocorico » (démarrage pendant l'essai, parrainage,
     * résumé de la semaine). titre = gros titre en haut de l'e-mail ; message = texte brut,
     * paragraphes séparés par une ligne vide. Ne lève jamais d'exception : false si l'envoi
     * échoue.
     */
    boolean sendMessageCocorico(String to, String fullName, String titre, String sujet, String message);

    // Même e-mail avec un en-tête List-Unsubscribe (lien vers la page où couper l'envoi) :
    // les messageries peuvent alors afficher leur propre bouton « Se désabonner ».
    default boolean sendMessageCocorico(String to, String fullName, String titre, String sujet, String message,
            String lienDesinscription) {
        return sendMessageCocorico(to, fullName, titre, sujet, message);
    }
}
