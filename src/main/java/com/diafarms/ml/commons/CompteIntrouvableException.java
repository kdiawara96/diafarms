package com.diafarms.ml.commons;

// Compte inexistant OU d'une autre ferme : même réponse (404 « Utilisateur introuvable »),
// pour ne jamais révéler qu'un compte existe ailleurs. Voir AccesCompte.
public class CompteIntrouvableException extends RuntimeException {
    public CompteIntrouvableException() {
        super("Utilisateur introuvable");
    }
}
