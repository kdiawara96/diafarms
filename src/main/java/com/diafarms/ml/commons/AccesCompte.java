package com.diafarms.ml.commons;

import java.util.Collection;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;

import lombok.RequiredArgsConstructor;

// Accès à un compte utilisateur désigné par son uniqueId (fiche, modification, mot de
// passe, QR, révocation, suppression, restauration). Règle unique :
//   - SUPER_ADMIN : n'importe quel compte ;
//   - le compte lui-même, seulement si l'endpoint l'autorise (soiMeme) ;
//   - sinon, uniquement un compte de LA MÊME ferme, et seulement pour un ADMIN de la ferme.
// Compte inexistant ou d'une autre ferme : CompteIntrouvableException (404, rien n'est
// révélé). Même ferme mais pas ADMIN : AccessDeniedException (403).
@Component
@RequiredArgsConstructor
public class AccesCompte {

    private final UtilisateursRepo utilisateursRepo;
    private final OtherService otherService;

    public static boolean estSuperAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null
                && u.getRoles().stream().anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    public static boolean estAdmin(Utilisateurs u) {
        return u != null && u.getRoles() != null
                && u.getRoles().stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()));
    }

    public Utilisateurs courant() {
        Utilisateurs u;
        try {
            u = otherService.getCurrentUser();
        } catch (Exception e) {
            u = null;
        }
        if (u == null) throw new AccessDeniedException("Non authentifié.");
        return u;
    }

    public Utilisateurs cible(String uniqueId, boolean soiMeme) {
        Utilisateurs courant = courant();
        Utilisateurs u = uniqueId == null ? null : utilisateursRepo.findByUniqueId(uniqueId).orElse(null);
        if (u == null) throw new CompteIntrouvableException();
        if (estSuperAdmin(courant)) return u;
        if (soiMeme && courant.getId().equals(u.getId())) return u;
        if (!FermeScope.memeFerme(u.getFarm(), courant)) throw new CompteIntrouvableException();
        if (!estAdmin(courant)) {
            throw new AccessDeniedException("Seul un administrateur de la ferme peut faire cette action.");
        }
        return u;
    }

    // Rôles qu'un appelant peut attribuer : jamais SUPER_ADMIN, sauf par un SUPER_ADMIN.
    public static void verifierRolesAttribuables(Utilisateurs courant, Collection<String> roles) {
        if (roles == null || estSuperAdmin(courant)) return;
        if (roles.stream().anyMatch(r -> r != null && "SUPER_ADMIN".equalsIgnoreCase(r.trim()))) {
            throw new AccessDeniedException("Le rôle SUPER_ADMIN ne peut pas être attribué.");
        }
    }
}
