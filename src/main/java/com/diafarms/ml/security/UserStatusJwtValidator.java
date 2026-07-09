package com.diafarms.ml.security;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.diafarms.ml.repository.UtilisateursRepo;

import lombok.RequiredArgsConstructor;

/**
 * Revalide le statut de l'utilisateur en base à chaque requête authentifiée,
 * pour que la révocation d'un compte coupe l'accès immédiatement plutôt que
 * d'attendre l'expiration naturelle du JWT (jusqu'à 7 jours).
 */
@Component
@RequiredArgsConstructor
public class UserStatusJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final UtilisateursRepo utilisateursRepo;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String uniqueId = token.getClaimAsString("uniqueId");

        // Le refresh token n'a pas ce claim et ne sert qu'à /auth (grantType=refreshToken),
        // pas à accéder aux endpoints protégés : on ne le bloque pas ici.
        if (uniqueId == null) {
            return OAuth2TokenValidatorResult.success();
        }

        return utilisateursRepo.findByUniqueId(uniqueId)
                .filter(u -> Boolean.TRUE.equals(u.getStatut()))
                .map(u -> OAuth2TokenValidatorResult.success())
                .orElseGet(() -> OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Compte suspendu ou introuvable", null)));
    }
}
