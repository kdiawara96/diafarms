package com.diafarms.ml.commons;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.diafarms.ml.security.CustomUserDetails;

@Component
public class SecurityUtils {

    public static String getCurrentUserUniqueId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        
        if (authentication == null || !authentication.isAuthenticated() 
                || authentication.getPrincipal().equals("anonymousUser")) {
            throw new IllegalStateException("Utilisateur non authentifié");
        }

        // Si tu utilises un JwtAuthenticationToken
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("uniqueId");
        }

        // Fallback si le principal est un UserDetails custom
        if (authentication.getPrincipal() instanceof CustomUserDetails userDetails) {
            return userDetails.getUniqueId();
        }

        throw new IllegalStateException("Impossible d'extraire le uniqueId du token");
    }

    public static String getCurrentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }
}