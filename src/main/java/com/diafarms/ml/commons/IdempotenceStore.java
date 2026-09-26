package com.diafarms.ml.commons;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

// Accès JDBC à idempotency_requests (voir IdempotencyRequest / IdempotenceFilter).
// Chaque instruction est validée seule (auto-commit, aucune transaction englobante) :
// l'enregistrement EN_COURS est visible des autres requêtes AVANT l'exécution du
// contrôleur, et c'est la contrainte unique (cle, utilisateur) qui départage deux envois
// simultanés de la même saisie.
@Component
@Slf4j
public class IdempotenceStore {

    public static final String EN_COURS = "EN_COURS";
    public static final String TERMINE = "TERMINE";

    // Au-delà, un EN_COURS est considéré abandonné (serveur arrêté en pleine exécution) :
    // la requête suivante le reprend au lieu de répondre 409 indéfiniment.
    private static final int MINUTES_ABANDON = 5;
    private static final int JOURS_RETENTION = 30;
    private static final long INTERVALLE_PURGE_MS = 60L * 60 * 1000;

    private final JdbcTemplate jdbc;
    private final AtomicLong dernierePurge = new AtomicLong(0);

    public IdempotenceStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record Enregistrement(long id, String methodeChemin, String hashCorps, String statut,
                                 Integer statutReponse, String corpsReponse, String typeContenu,
                                 LocalDateTime createdAt) {}

    public Long farmIdDe(String username) {
        List<Long> ids = jdbc.query("SELECT farm_id FROM utilisateurs WHERE username = ?",
                (rs, i) -> (Long) rs.getObject(1, Long.class), username);
        return ids.isEmpty() ? null : ids.get(0);
    }

    /** Insère l'enregistrement EN_COURS ; renvoie son id, ou null si la clé existe déjà. */
    public Long reserver(String cle, String utilisateur, Long farmId, String methodeChemin, String hashCorps) {
        purgerSiBesoin();
        try {
            jdbc.update("INSERT INTO idempotency_requests (cle, utilisateur, farm_id, methode_chemin, hash_corps, statut, created_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    cle, utilisateur, farmId, methodeChemin, hashCorps, EN_COURS, Timestamp.valueOf(LocalDateTime.now()));
        } catch (DuplicateKeyException e) {
            return null;
        }
        return trouver(cle, utilisateur).map(Enregistrement::id).orElse(null);
    }

    public java.util.Optional<Enregistrement> trouver(String cle, String utilisateur) {
        List<Enregistrement> l = jdbc.query("SELECT id, methode_chemin, hash_corps, statut, statut_reponse, corps_reponse, type_contenu, created_at "
                        + "FROM idempotency_requests WHERE cle = ? AND utilisateur = ?",
                (rs, i) -> new Enregistrement(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        (Integer) rs.getObject(5, Integer.class), rs.getString(6), rs.getString(7),
                        rs.getTimestamp(8).toLocalDateTime()),
                cle, utilisateur);
        return l.stream().findFirst();
    }

    /** Reprend un EN_COURS abandonné ; true si cette requête en devient propriétaire. */
    public boolean reprendreAbandonne(long id, String methodeChemin, String hashCorps) {
        LocalDateTime maintenant = LocalDateTime.now();
        return jdbc.update("UPDATE idempotency_requests SET created_at = ?, methode_chemin = ?, hash_corps = ? "
                        + "WHERE id = ? AND statut = ? AND created_at < ?",
                Timestamp.valueOf(maintenant), methodeChemin, hashCorps, id, EN_COURS,
                Timestamp.valueOf(maintenant.minusMinutes(MINUTES_ABANDON))) == 1;
    }

    public boolean estAbandonne(Enregistrement e) {
        return EN_COURS.equals(e.statut()) && e.createdAt().isBefore(LocalDateTime.now().minusMinutes(MINUTES_ABANDON));
    }

    public void terminer(long id, int statutReponse, String corpsReponse, String typeContenu) {
        jdbc.update("UPDATE idempotency_requests SET statut = ?, statut_reponse = ?, corps_reponse = ?, type_contenu = ? WHERE id = ?",
                TERMINE, statutReponse, corpsReponse, typeContenu, id);
    }

    public void supprimer(long id) {
        jdbc.update("DELETE FROM idempotency_requests WHERE id = ?", id);
    }

    // Purge « au fil de l'eau » : au plus une fois par heure, lors d'une nouvelle réservation.
    private void purgerSiBesoin() {
        long maintenant = System.currentTimeMillis();
        long derniere = dernierePurge.get();
        if (maintenant - derniere < INTERVALLE_PURGE_MS || !dernierePurge.compareAndSet(derniere, maintenant)) {
            return;
        }
        try {
            int n = jdbc.update("DELETE FROM idempotency_requests WHERE created_at < ?",
                    Timestamp.valueOf(LocalDateTime.now().minusDays(JOURS_RETENTION)));
            if (n > 0) log.info("Idempotence : {} enregistrement(s) de plus de {} jours purgé(s)", n, JOURS_RETENTION);
        } catch (RuntimeException e) {
            log.warn("Idempotence : purge impossible ({})", e.getMessage());
        }
    }
}
