package com.diafarms.ml.security;

import java.io.ByteArrayInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.diafarms.ml.commons.IdempotenceStore;
import com.diafarms.ml.commons.IdempotenceStore.Enregistrement;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

// Idempotence des écritures envoyées avec l'en-tête Idempotency-Key (appli mobile : les
// saisies hors ligne sont envoyées une à une ; si la réponse se perd, la saisie est
// renvoyée plus tard et ne doit pas être créée une deuxième fois).
//
// Sans l'en-tête : rien ne change (web, anciens APK). Avec l'en-tête, sur POST/PUT/PATCH :
// - première requête : enregistrement EN_COURS (contrainte unique clé+utilisateur, validé
//   tout de suite), exécution normale, puis réponse mémorisée si 2xx ou 4xx ; sur 5xx ou
//   exception, l'enregistrement est supprimé pour qu'un nouvel envoi ré-exécute la saisie ;
// - même clé, même chemin, même corps, déjà TERMINE : la réponse mémorisée est rejouée
//   telle quelle (statut + corps) avec l'en-tête Idempotency-Replayed: true, sans rien
//   ré-exécuter ;
// - même clé mais autre chemin ou autre corps : 422 ;
// - même clé encore EN_COURS (envoi simultané) : 409, le téléphone réessaiera plus tard.
// Placé après l'authentification (le username du JWT fait partie de la clé).
@Slf4j
public class IdempotenceFilter extends OncePerRequestFilter {

    public static final String ENTETE_CLE = "Idempotency-Key";
    public static final String ENTETE_REJOUE = "Idempotency-Replayed";

    private static final Pattern CLE_VALIDE = Pattern.compile("[A-Za-z0-9._:-]{1,100}");
    private static final ObjectMapper JSON_TRIE = new ObjectMapper()
            .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final IdempotenceStore store;

    public IdempotenceFilter(IdempotenceStore store) {
        this.store = store;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String m = request.getMethod();
        if (!"POST".equalsIgnoreCase(m) && !"PUT".equalsIgnoreCase(m) && !"PATCH".equalsIgnoreCase(m)) return true;
        if (request.getHeader(ENTETE_CLE) == null) return true;
        // Corps lu en entier par ce filtre : les formulaires (multipart, urlencoded) dont
        // les paramètres sont lus par le conteneur ne sont pas concernés.
        String ct = request.getContentType();
        return ct != null && (ct.toLowerCase().startsWith("multipart/")
                || ct.toLowerCase().startsWith("application/x-www-form-urlencoded"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            chain.doFilter(request, response); // non authentifié : la suite de la chaîne répondra 401
            return;
        }
        String cle = request.getHeader(ENTETE_CLE).trim();
        if (!CLE_VALIDE.matcher(cle).matches()) {
            ecrireErreur(response, HttpServletResponse.SC_BAD_REQUEST, "En-tête Idempotency-Key invalide",
                    "L'en-tête Idempotency-Key doit contenir de 1 à 100 caractères (lettres, chiffres, . _ : -).");
            return;
        }
        String utilisateur = jwt.getSubject();
        byte[] corps = request.getInputStream().readAllBytes();
        String methodeChemin = request.getMethod().toUpperCase() + " " + request.getRequestURI()
                + (request.getQueryString() != null ? "?" + request.getQueryString() : "");
        String hash = hashCorps(corps);

        Long id = null;
        // Deux tours au plus : si l'enregistrement concurrent disparaît entre notre
        // tentative d'insertion et sa lecture (5xx côté concurrent), on retente l'insertion.
        for (int tour = 0; tour < 2 && id == null; tour++) {
            id = store.reserver(cle, utilisateur, store.farmIdDe(utilisateur), methodeChemin, hash);
            if (id != null) break;
            Enregistrement e = store.trouver(cle, utilisateur).orElse(null);
            if (e == null) continue;
            if (!e.methodeChemin().equals(methodeChemin) || !e.hashCorps().equals(hash)) {
                ecrireErreur(response, 422, "Clé déjà utilisée pour une autre saisie",
                        "Clé déjà utilisée pour une autre saisie");
                return;
            }
            if (IdempotenceStore.TERMINE.equals(e.statut())) {
                rejouer(response, e);
                return;
            }
            if (store.estAbandonne(e) && store.reprendreAbandonne(e.id(), methodeChemin, hash)) {
                id = e.id();
                break;
            }
            ecrireErreur(response, HttpServletResponse.SC_CONFLICT, "Saisie en cours de traitement, réessayez",
                    "Saisie en cours de traitement, réessayez");
            return;
        }
        if (id == null) {
            ecrireErreur(response, HttpServletResponse.SC_CONFLICT, "Saisie en cours de traitement, réessayez",
                    "Saisie en cours de traitement, réessayez");
            return;
        }

        ContentCachingResponseWrapper reponse = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(new CorpsRelu(request, corps), reponse);
        } catch (IOException | ServletException | RuntimeException | Error ex) {
            liberer(id, cle); // exception = erreur serveur : un nouvel envoi ré-exécutera
            throw ex;
        }
        int statut = reponse.getStatus();
        if ((statut >= 200 && statut < 300) || (statut >= 400 && statut < 500)) {
            try {
                store.terminer(id, statut, new String(reponse.getContentAsByteArray(), StandardCharsets.UTF_8),
                        reponse.getContentType());
            } catch (RuntimeException ex) {
                // La saisie est faite mais sa réponse n'a pas pu être mémorisée : on garde
                // l'enregistrement EN_COURS (un renvoi reçoit 409 puis, après abandon, est
                // ré-exécuté) plutôt que de le supprimer et risquer un doublon immédiat.
                log.error("Idempotence : réponse de la clé {} non mémorisée ({})", cle, ex.getMessage());
            }
        } else {
            liberer(id, cle);
        }
        reponse.copyBodyToResponse();
    }

    private void liberer(long id, String cle) {
        try {
            store.supprimer(id);
        } catch (RuntimeException ex) {
            log.warn("Idempotence : suppression de la clé {} impossible ({})", cle, ex.getMessage());
        }
    }

    private void rejouer(HttpServletResponse response, Enregistrement e) throws IOException {
        response.setStatus(e.statutReponse());
        response.setHeader(ENTETE_REJOUE, "true");
        String corps = e.corpsReponse();
        if (corps == null || corps.isEmpty()) {
            if (e.statutReponse() >= 400) {
                ecrireErreur(response, e.statutReponse(), "Requête refusée", "Requête refusée (" + e.statutReponse() + ").");
            }
            return;
        }
        response.setContentType(e.typeContenu() != null ? e.typeContenu() : "application/json;charset=UTF-8");
        byte[] b = corps.getBytes(StandardCharsets.UTF_8);
        response.setContentLength(b.length);
        response.getOutputStream().write(b);
    }

    // Même enveloppe que ApiResponse (message, status, errors) : le web et le mobile
    // affichent errors[0] tel quel.
    private static void ecrireErreur(HttpServletResponse response, int statut, String message, String erreur) throws IOException {
        response.setStatus(statut);
        response.setContentType("application/json;charset=UTF-8");
        String json = JSON_TRIE.writeValueAsString(java.util.Map.of(
                "message", message, "status", statut, "errors", java.util.List.of(erreur)));
        response.getWriter().write(json);
    }

    // SHA-256 du JSON normalisé (clés triées, espaces supprimés) : un renvoi de la même
    // saisie reste reconnu même si l'ordre des champs change ; corps non JSON : octets bruts.
    static String hashCorps(byte[] corps) {
        byte[] normalise = corps;
        if (corps.length > 0) {
            try {
                normalise = JSON_TRIE.writeValueAsBytes(JSON_TRIE.readValue(corps, Object.class));
            } catch (IOException e) {
                normalise = corps;
            }
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(normalise));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // Requête dont le corps (déjà lu pour le hash) peut être relu par le contrôleur.
    private static final class CorpsRelu extends HttpServletRequestWrapper {
        private final byte[] corps;

        CorpsRelu(HttpServletRequest request, byte[] corps) {
            super(request);
            this.corps = corps;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(corps);
            return new ServletInputStream() {
                @Override public int read() { return in.read(); }
                @Override public int read(byte[] b, int off, int len) { return in.read(b, off, len); }
                @Override public boolean isFinished() { return in.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener l) { throw new UnsupportedOperationException(); }
            };
        }

        @Override
        public BufferedReader getReader() {
            String cs = getCharacterEncoding() != null ? getCharacterEncoding() : StandardCharsets.UTF_8.name();
            return new BufferedReader(new InputStreamReader(getInputStream(), java.nio.charset.Charset.forName(cs)));
        }

        @Override
        public int getContentLength() { return corps.length; }

        @Override
        public long getContentLengthLong() { return corps.length; }
    }
}
