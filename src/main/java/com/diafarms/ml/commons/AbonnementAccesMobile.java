package com.diafarms.ml.commons;

import java.time.LocalDate;

import org.springframework.stereotype.Component;

import com.diafarms.ml.models.Abonnement;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.AbonnementConfigRepo;
import com.diafarms.ml.repository.AbonnementRepo;

import lombok.RequiredArgsConstructor;

// Refus des NOUVELLES connexions mobiles d'une ferme suspendue, ou expirée après le délai
// de grâce (voir AbonnementEcheance). Appliqué seulement :
//   - à la connexion par mot de passe avec X-Client-Type: mobile (AuthImpl, pas au refresh) ;
//   - au scan d'un QR par le serveur (/qrcode/scan) et à la génération d'un QR (/qrcode/generate).
// Rien d'autre n'est bloqué : un téléphone déjà connecté garde son token et continue
// d'envoyer ses saisies en attente, rien n'est perdu. La connexion web reste ouverte
// (page Abonnement pour renouveler ; AbonnementGate bloque le reste du web).
// SUPER_ADMIN et comptes sans ferme : jamais concernés. Ferme sans ligne d'abonnement
// (jamais revenue depuis l'arrivée des abonnements) : pas bloquée, son essai démarrera.
@Component
@RequiredArgsConstructor
public class AbonnementAccesMobile {

    public static final String MESSAGE_SUSPENDU =
            "L'accès de votre ferme est suspendu. Contactez-nous sur WhatsApp au +223 83 91 86 99.";
    public static final String MESSAGE_EXPIRE =
            "L'abonnement de votre ferme est terminé. Les saisies déjà faites sur ce téléphone seront envoyées dès le "
            + "renouvellement. Renouvelez depuis l'application web ou contactez-nous sur WhatsApp au +223 83 91 86 99.";

    private final AbonnementRepo abonnementRepo;
    private final AbonnementConfigRepo configRepo;

    // Message de refus, ou null si la connexion mobile est permise.
    public String motifRefus(Utilisateurs u) {
        if (u == null || u.getFarm() == null) return null;
        boolean superAdmin = u.getRoles() != null && u.getRoles().stream()
                .anyMatch(r -> "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
        if (superAdmin) return null;
        Abonnement a = abonnementRepo.findByFarm_Id(u.getFarm().getId()).orElse(null);
        if (a == null) return null;
        AbonnementEcheance.Etat etat = AbonnementEcheance.calculer(a, configRepo.findFirstByOrderByIdAsc(), LocalDate.now());
        if (etat.suspendu()) return MESSAGE_SUSPENDU;
        if (etat.expire()) return MESSAGE_EXPIRE;
        return null;
    }
}
