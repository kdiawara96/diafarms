package com.diafarms.ml.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Compte les essais ratés (connexion, code « mot de passe oublié »…) par clé
 * (identifiant, e-mail, adresse IP) et bloque la clé quand elle dépasse son
 * maximum dans la fenêtre de temps. Sans ça, un programme peut essayer les
 * 1 000 000 codes à 6 chiffres ou des milliers de mots de passe.
 *
 * En mémoire : un seul serveur Cocorico tourne, et un redémarrage qui remet
 * les compteurs à zéro est sans conséquence.
 */
@Component
public class LimiteurTentatives {

    private static final int TAILLE_MAX = 50_000;

    private static final class Compteur {
        int essais;
        Instant debut;
        Duration fenetre;
    }

    private final Map<String, Compteur> compteurs = new ConcurrentHashMap<>();

    /** Minutes restantes avant de pouvoir réessayer, ou 0 si la clé n'est pas bloquée. */
    public long minutesRestantes(String cle, int max) {
        Compteur c = compteurs.get(cle);
        if (c == null) return 0;
        synchronized (c) {
            Instant fin = c.debut.plus(c.fenetre);
            if (Instant.now().isAfter(fin)) {
                compteurs.remove(cle, c);
                return 0;
            }
            if (c.essais < max) return 0;
            return Math.max(1, Duration.between(Instant.now(), fin).toMinutes() + 1);
        }
    }

    public boolean estBloque(String cle, int max) {
        return minutesRestantes(cle, max) > 0;
    }

    /** Enregistre un essai raté ; renvoie true si la clé vient d'atteindre son maximum. */
    public boolean echec(String cle, int max, Duration fenetre) {
        if (compteurs.size() > TAILLE_MAX) nettoyer();
        Compteur c = compteurs.computeIfAbsent(cle, k -> {
            Compteur n = new Compteur();
            n.debut = Instant.now();
            n.fenetre = fenetre;
            return n;
        });
        synchronized (c) {
            if (Instant.now().isAfter(c.debut.plus(c.fenetre))) {
                c.essais = 0;
                c.debut = Instant.now();
                c.fenetre = fenetre;
            }
            c.essais++;
            return c.essais >= max;
        }
    }

    public void effacer(String cle) {
        compteurs.remove(cle);
    }

    private void nettoyer() {
        Instant maintenant = Instant.now();
        compteurs.entrySet().removeIf(e -> maintenant.isAfter(e.getValue().debut.plus(e.getValue().fenetre)));
    }

    /**
     * Adresse du visiteur. Derrière Nginx Proxy Manager, la connexion vient
     * toujours du proxy : la vraie adresse est dans X-Real-IP, que le proxy
     * réécrit lui-même (le visiteur ne peut donc pas la falsifier).
     */
    public static String adresseIp(HttpServletRequest requete) {
        String ip = requete.getHeader("X-Real-IP");
        if (ip == null || ip.isBlank()) ip = requete.getRemoteAddr();
        return ip == null ? "inconnue" : ip.trim();
    }
}
