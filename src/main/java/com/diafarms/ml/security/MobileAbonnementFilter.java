package com.diafarms.ml.security;

import java.io.IOException;
import java.time.LocalDate;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import com.diafarms.ml.commons.AbonnementAccesMobile;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

// Option A (ferme suspendue, ou expirée après la grâce, voir AbonnementAccesMobile) pour
// l'APPLICATION MOBILE déjà connectée :
//   - écritures (POST, PUT, PATCH, DELETE) : toujours acceptées, les saisies en attente
//     sur un téléphone partent et ne sont jamais perdues (SyncManager n'utilise aucun GET) ;
//   - GET /notifications/list et /notifications/projet/... : 200 avec UNE alerte (le
//     message), affichée par le téléphone (carte « Alertes » de l'accueil, notification
//     Android via AlertCheckWorker) ;
//   - tout autre GET : 403 avec le message. L'APK 1.34 ignore en silence un GET en échec
//     et garde ses données en cache (jamais de déconnexion, jamais d'effacement), et un
//     403 sur un GET n'arrête jamais l'envoi des saisies.
// Reconnaître le mobile : l'APK 1.34 n'envoie PAS d'en-tête X-Client-Type ; il utilise
// toujours le jeton de son QR, signé par le serveur avec le claim type = "QR_CODE" (voir
// QRCodeService). On accepte aussi X-Client-Type: mobile (futurs builds). Le web n'a
// jamais ni l'un ni l'autre : il n'est jamais touché ici (AbonnementGate s'en charge).
// Pendant la grâce, pour une ferme active, un SUPER_ADMIN ou un compte sans ferme : rien.
// Signal explicite pour le téléphone (APK 1.35+) : en-tête X-Abonnement-Bloque =
// SUSPENDU ou EXPIRE sur ces deux réponses (le 403 et l'alerte unique). Le téléphone
// affiche alors un bandeau et masque ses chiffres en cache ; l'APK 1.34 l'ignore.
public class MobileAbonnementFilter extends OncePerRequestFilter {

    public static final String ENTETE_BLOQUE = "X-Abonnement-Bloque";

    private final AbonnementAccesMobile acces;

    public MobileAbonnementFilter(AbonnementAccesMobile acces) {
        this.acces = acces;
    }

    static boolean estMobile(HttpServletRequest request, Jwt jwt) {
        return "QR_CODE".equals(jwt.getClaimAsString("type"))
                || "mobile".equalsIgnoreCase(request.getHeader("X-Client-Type"));
    }

    private static String json(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '"' || c == '\\') b.append('\\');
            b.append(c);
        }
        return b.append('"').toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!"GET".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt) || !estMobile(request, jwt)) {
            chain.doFilter(request, response);
            return;
        }
        String etat;
        try {
            etat = acces.etatLecture(jwt.getSubject());
        } catch (Exception e) {
            etat = null; // en cas de doute, on ne bloque jamais
        }
        String uri = request.getRequestURI();
        if (etat == null || uri.endsWith("/abonnements/moi")) {
            chain.doFilter(request, response);
            return;
        }
        String message = AbonnementAccesMobile.messageLecture(etat);
        response.setContentType("application/json;charset=UTF-8");
        response.setHeader(ENTETE_BLOQUE, etat);
        if (uri.contains("/notifications/list") || uri.contains("/notifications/projet/")) {
            // Une seule alerte, clé du jour : notification Android une fois par jour.
            response.setStatus(HttpServletResponse.SC_OK);
            response.getWriter().write("{\"message\":\"Alertes\",\"status\":200,\"data\":[{\"key\":\"abonnement-bloque-"
                    + LocalDate.now() + "\",\"type\":\"ABONNEMENT\",\"level\":\"CRITIQUE\",\"message\":" + json(message)
                    + ",\"read\":false}]}");
            return;
        }
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.getWriter().write("{\"message\":" + json(message) + ",\"status\":403,\"data\":null,\"errors\":["
                + json(message) + "]}");
    }
}
