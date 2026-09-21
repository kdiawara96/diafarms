package com.diafarms.ml.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Comptes en CONSULTATION SEULE (Utilisateurs.consultationSeule, ex: comptes de démonstration
// remis à des visiteurs) : toute requête qui n'est pas une simple lecture est refusée ICI, avant
// d'atteindre un contrôleur, donc aucune création/modification/suppression ne peut passer, quel
// que soit l'écran ou l'appli (web, mobile, appel direct). Seul le changement de mot de passe
// reste permis : la première connexion l'impose (mustChangePassword).
// Placé après le filtre bearer-token (voir SecurityConfiguration) pour que le JWT soit déjà lu.
public class ConsultationSeuleFilter extends OncePerRequestFilter {

    private final UtilisateursRepo utilisateursRepo;

    public ConsultationSeuleFilter(UtilisateursRepo utilisateursRepo) {
        this.utilisateursRepo = utilisateursRepo;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        boolean lecture = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
        String uri = request.getRequestURI();
        // Exceptions : changer son propre mot de passe, et marquer SES notifications comme lues
        // (état personnel, pas une donnée de la ferme).
        boolean autorise = uri.endsWith("/users/change-password") || uri.contains("/notifications/");
        if (!lecture && !autorise) {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
                Utilisateurs u = utilisateursRepo.findByUsername(jwt.getSubject()).orElse(null);
                if (u != null && Boolean.TRUE.equals(u.getConsultationSeule())) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    // Même enveloppe que ApiResponse : le web affiche errors[0] tel quel.
                    response.getWriter().write("{\"message\":\"Consultation seule\",\"status\":403,"
                            + "\"errors\":[\"Ce compte est en consultation seule : vous pouvez tout consulter, mais rien créer, modifier ni supprimer.\"]}");
                    return;
                }
            }
        }
        chain.doFilter(request, response);
    }
}
