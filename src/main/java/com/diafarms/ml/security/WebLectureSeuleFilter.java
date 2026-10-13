package com.diafarms.ml.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.diafarms.ml.commons.AbonnementAccesMobile;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Application WEB d'une ferme expirée après le délai de grâce : LECTURE SEULE.
// La ferme garde l'accès à ses données (consulter, exporter, rapports PDF), mais aucune
// création, modification ni suppression ne passe, quel que soit l'écran (le serveur
// refuse, pas seulement la page). Exceptions : ce qui sert à reprendre l'abonnement
// (déclarer une recharge, code de parrainage), son mot de passe et ses notifications.
// Ferme suspendue par l'équipe Cocorico : la page web reste entièrement bloquée
// (AbonnementGate) et le serveur refuse aussi les écritures ici.
// L'application mobile n'est jamais touchée ici : voir MobileAbonnementFilter (les
// saisies déjà faites sur un téléphone partent toujours, rien n'est perdu).
public class WebLectureSeuleFilter extends OncePerRequestFilter {

    public static final String MESSAGE_EXPIRE =
            "L'essai gratuit ou le crédit de votre ferme est terminé : Cocorico est en lecture seule. Vous pouvez tout consulter "
            + "et exporter, mais pas enregistrer. Rechargez depuis la page Abonnement pour reprendre les saisies.";

    private final AbonnementAccesMobile acces;

    public WebLectureSeuleFilter(AbonnementAccesMobile acces) {
        this.acces = acces;
    }

    static boolean estAutorise(String uri) {
        return uri.contains("/abonnements/declarer-paiement")
                || uri.contains("/croissance/parrainage")
                || uri.contains("/notifications/")
                || uri.contains("/users/change-password")
                || uri.contains("/auth");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String method = request.getMethod();
        boolean lecture = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method)
                || "OPTIONS".equalsIgnoreCase(method);
        if (lecture || estAutorise(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || MobileAbonnementFilter.estMobile(request, jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String etat;
        try {
            etat = acces.etatLecture(jwt.getSubject());
        } catch (Exception e) {
            etat = null; // en cas de doute, on ne bloque jamais
        }
        if (etat == null) {
            chain.doFilter(request, response);
            return;
        }
        String message = AbonnementAccesMobile.SUSPENDU.equals(etat) ? AbonnementAccesMobile.MESSAGE_SUSPENDU : MESSAGE_EXPIRE;
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(MobileAbonnementFilter.ENTETE_BLOQUE, etat);
        // Même enveloppe que ApiResponse : le web affiche errors[0] tel quel.
        response.getWriter().write("{\"message\":\"Lecture seule\",\"status\":403,\"data\":null,\"errors\":[\""
                + message.replace("\"", "\\\"") + "\"]}");
    }
}
