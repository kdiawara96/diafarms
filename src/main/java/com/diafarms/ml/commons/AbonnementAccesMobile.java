package com.diafarms.ml.commons;

import java.time.LocalDate;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.AbonnementRepo;
import com.diafarms.ml.repository.UtilisateursRepo;

import lombok.RequiredArgsConstructor;

// Application mobile d'une ferme suspendue, ou expirée après le délai de grâce (voir
// AbonnementEcheance, seule source de vérité). Deux usages :
//
// 1. Nouvelle connexion (motifRefus) : refusée à la connexion par mot de passe avec
//    X-Client-Type: mobile (AuthImpl, jamais au refresh), à la génération et au scan
//    serveur d'un QR. La connexion web reste ouverte (page Abonnement pour renouveler).
//
// 2. Téléphones déjà connectés (option A, etatLecture + MobileAbonnementFilter) : les
//    LECTURES (GET) sont refusées avec un message, les ENVOIS de saisies (POST/PUT) sont
//    toujours acceptés : rien de ce qui est sur un téléphone n'est jamais perdu.
//
// SUPER_ADMIN et comptes sans ferme : jamais concernés. Ferme sans ligne d'abonnement
// (jamais revenue depuis l'arrivée des abonnements) : pas concernée, son essai démarrera.
@Component
@RequiredArgsConstructor
public class AbonnementAccesMobile {

    public static final String SUSPENDU = "SUSPENDU";
    public static final String EXPIRE = "EXPIRE";

    public static final String MESSAGE_SUSPENDU =
            "L'accès de votre ferme est suspendu. Contactez-nous sur WhatsApp au +223 83 91 86 99.";
    public static final String MESSAGE_EXPIRE =
            "L'abonnement de votre ferme est terminé. Les saisies déjà faites sur ce téléphone ne sont pas perdues. "
            + "Renouvelez depuis l'application web ou contactez-nous sur WhatsApp au +223 83 91 86 99.";

    // Lectures refusées sur un téléphone déjà connecté (affiché dans les alertes du téléphone).
    public static final String MESSAGE_LECTURE_EXPIRE =
            "L'abonnement de votre ferme est terminé : les données ne sont plus mises à jour sur ce téléphone. "
            + "Vos saisies sont toujours envoyées, rien n'est perdu. Renouvelez depuis l'application web "
            + "ou contactez-nous sur WhatsApp au +223 83 91 86 99.";
    public static final String MESSAGE_LECTURE_SUSPENDU =
            "L'accès de votre ferme est suspendu : les données ne sont plus mises à jour sur ce téléphone. "
            + "Vos saisies sont toujours envoyées, rien n'est perdu. Contactez-nous sur WhatsApp au +223 83 91 86 99.";

    // État par compte (username) gardé 60 s au plus : un GET mobile ne coûte pas une
    // requête en base à chaque fois. Vidé pour la ferme concernée à chaque action de la
    // console et à chaque validation de paiement (invalider).
    static final long DUREE_CACHE_MS = 60_000;

    private record Entree(Long farmId, String etat, long expireA) {}

    private final ConcurrentHashMap<String, Entree> cache = new ConcurrentHashMap<>();

    private final AbonnementRepo abonnementRepo;
    private final AbonnementConfigRepo configRepo;
    private final UtilisateursRepo utilisateursRepo;

    private static boolean estSuperAdmin(Utilisateurs u) {
        return u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
    }

    // SUSPENDU, EXPIRE (après la grâce) ou null (accès normal).
    private String etat(Utilisateurs u) {
        if (u == null || u.getFarm() == null || estSuperAdmin(u)) return null;
        Abonnement a = abonnementRepo.findByFarm_Id(u.getFarm().getId()).orElse(null);
        if (a == null) return null;
        AbonnementEcheance.Etat e = AbonnementEcheance.calculer(a, configRepo.findFirstByOrderByIdAsc(), LocalDate.now());
        if (e.suspendu()) return SUSPENDU;
        if (e.expire()) return EXPIRE;
        return null;
    }

    // Message de refus d'une NOUVELLE connexion mobile, ou null si elle est permise.
    public String motifRefus(Utilisateurs u) {
        String e = etat(u);
        if (SUSPENDU.equals(e)) return MESSAGE_SUSPENDU;
        if (EXPIRE.equals(e)) return MESSAGE_EXPIRE;
        return null;
    }

    // État d'un compte pour les lectures mobiles (MobileAbonnementFilter), avec cache.
    public String etatLecture(String username) {
        if (username == null) return null;
        long maintenant = System.currentTimeMillis();
        Entree en = cache.get(username);
        if (en != null && en.expireA() > maintenant) return en.etat();
        Utilisateurs u = utilisateursRepo.findByUsername(username).orElse(null);
        Long farmId = u != null && u.getFarm() != null ? u.getFarm().getId() : null;
        String etat = etat(u);
        cache.put(username, new Entree(farmId, etat, maintenant + DUREE_CACHE_MS));
        return etat;
    }

    public static String messageLecture(String etat) {
        return SUSPENDU.equals(etat) ? MESSAGE_LECTURE_SUSPENDU : MESSAGE_LECTURE_EXPIRE;
    }

    // L'abonnement de cette ferme vient de changer : les téléphones voient le nouvel état tout de suite.
    public void invalider(Long farmId) {
        if (farmId == null) return;
        cache.values().removeIf(e -> farmId.equals(e.farmId()));
    }

    // À appeler dans la transaction qui modifie l'abonnement : vidé tout de suite ET après
    // le commit (une lecture entre les deux ne garde pas l'ancien état 60 s).
    public void invaliderApresCommit(Long farmId) {
        invalider(farmId);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            invalider(farmId);
                        }
                    });
        }
    }
}
