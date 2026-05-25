package com.diafarms.ml.ServiceImpl;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class OtherService {

    public final UtilisateursRepo utilisateursRepo;


    /**
     * Gets the currently authenticated user.
     *
     * @return the authenticated user
     */
    public Utilisateurs getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getSubject();
            return utilisateursRepo.findByUsername(username).orElse(null);
        }
        return null;
    }
}
