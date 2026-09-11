package com.diafarms.ml.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

@Component
public class CookieAuthUtils {

    // Préfixés "diafarms_" : "access_token"/"refresh_token" sont des noms trop
    // génériques, entrés en collision avec une autre application partageant le
    // domaine parent batimanager.net (nom de cookie identique, portée large côté
    // navigateur) — le navigateur envoyait alors le jeton de CETTE AUTRE appli à la
    // place du nôtre, provoquant des 401 aléatoires ("Compte suspendu ou
    // introuvable" : le JWT décodait, mais avec des claims d'une tout autre appli).
    public static final String ACCESS_COOKIE = "diafarms_access_token";
    public static final String REFRESH_COOKIE = "diafarms_refresh_token";

    @Value("${app.cookie.secure:true}")
    private boolean cookieSecure;

    @Value("${app.cookie.domain:}")
    private String cookieDomain;

    public void setAccessCookie(HttpServletResponse response, String token, long maxAgeSeconds) {
        addCookie(response, ACCESS_COOKIE, token, maxAgeSeconds);
    }

    public void setRefreshCookie(HttpServletResponse response, @NonNull String token, long maxAgeSeconds) {
        addCookie(response, REFRESH_COOKIE, token, maxAgeSeconds);
    }

    public void clearAuthCookies(HttpServletResponse response) {
        addCookie(response, ACCESS_COOKIE, "", 0);
        addCookie(response, REFRESH_COOKIE, "", 0);
    }

    private void addCookie(HttpServletResponse response, @NonNull String name, @NonNull String value, long maxAgeSeconds) {
        ResponseCookie.ResponseCookieBuilder builder = ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite("Lax")
                .path("/")
                .maxAge(maxAgeSeconds);

        if (cookieDomain != null && !cookieDomain.isBlank()) {
            builder.domain(cookieDomain);
        }

        response.addHeader(HttpHeaders.SET_COOKIE, builder.build().toString());
    }
}
