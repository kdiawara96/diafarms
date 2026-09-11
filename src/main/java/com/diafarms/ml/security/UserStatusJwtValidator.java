package com.diafarms.ml.security;

import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.diafarms.ml.commons.AppAccessRules;
import com.diafarms.ml.models.FarmAppSettings;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.FarmAppSettingsRepo;
import com.diafarms.ml.repository.UtilisateursRepo;

import lombok.RequiredArgsConstructor;

/**
 * Revalide le statut de l'utilisateur en base à chaque requête authentifiée,
 * pour que la révocation d'un compte coupe l'accès immédiatement plutôt que
 * d'attendre l'expiration naturelle du JWT (jusqu'à 7 jours).
 *
 * Pour un token issu d'un QR (claim "type" = "QR_CODE", voir QRCodeService), on
 * revalide aussi l'accès mobile à chaque requête : un token de QR déjà distribué
 * doit cesser de fonctionner dès que l'admin désactive l'accès mobile du rôle,
 * sans attendre son expiration — contrairement à un token issu d'un login
 * mot de passe (web ou mobile), déjà filtré une seule fois à l'émission (voir
 * AuthImpl.jwt) et qui n'a pas besoin de ce coût de revalidation à chaque requête.
 */
@Component
@RequiredArgsConstructor
public class UserStatusJwtValidator implements OAuth2TokenValidator<Jwt> {

    private final UtilisateursRepo utilisateursRepo;
    private final FarmAppSettingsRepo farmAppSettingsRepo;

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String uniqueId = token.getClaimAsString("uniqueId");

        // Le refresh token n'a pas ce claim et ne sert qu'à /auth (grantType=refreshToken),
        // pas à accéder aux endpoints protégés : on ne le bloque pas ici.
        if (uniqueId == null) {
            return OAuth2TokenValidatorResult.success();
        }

        Utilisateurs user = utilisateursRepo.findByUniqueId(uniqueId).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getStatut())) {
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Compte suspendu ou introuvable", null));
        }

        boolean isQrCode = "QR_CODE".equals(token.getClaimAsString("type"));

        // Révocation immédiate à la déconnexion (voir Utilisateurs.tokenVersion /
        // authControllers.logout) — ne s'applique jamais à un token QR mobile,
        // mécanisme distinct et volontairement persistant (pas de "déconnexion" côté
        // mobile qui doive le couper).
        if (!isQrCode) {
            Integer tokenVersionDuToken = token.getClaim("tokenVersion");
            int versionActuelle = user.getTokenVersion() != null ? user.getTokenVersion() : 0;
            int versionDuToken = tokenVersionDuToken != null ? tokenVersionDuToken : 0;
            if (versionDuToken != versionActuelle) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Session expirée, veuillez vous reconnecter.", null));
            }
        }

        if (isQrCode && user.getFarm() != null) {
            Set<String> roles = user.getRoles() == null ? Set.of()
                    : user.getRoles().stream().map(r -> r.getRole()).collect(Collectors.toSet());
            FarmAppSettings settings = farmAppSettingsRepo.findByFarm_Id(user.getFarm().getId()).orElse(null);
            if (!AppAccessRules.canAccessMobile(settings, roles)) {
                return OAuth2TokenValidatorResult.failure(
                        new OAuth2Error("invalid_token", "Accès mobile désactivé pour ce rôle", null));
            }
        }

        return OAuth2TokenValidatorResult.success();
    }
}
