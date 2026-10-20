package com.diafarms.ml.ServiceImpl;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.commons.AccesCompte;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.security.LimiteurTentatives;
import com.diafarms.ml.services.EmailService;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Changement d'e-mail d'un utilisateur, validé par un code à 6 chiffres envoyé à la
 * NOUVELLE adresse. L'e-mail sert à se connecter et à recevoir le code « mot de passe
 * oublié » : le changer sans preuve permettait de prendre un compte (mettre sa propre
 * adresse puis demander un nouveau mot de passe) ou de s'en couper par une faute de
 * frappe. Tant que le code n'est pas saisi, l'ancien e-mail reste celui du compte.
 * Une fois le changement fait, l'ancienne adresse est prévenue.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChangementEmailService {

    private static final int CODE_VALIDITE_MINUTES = 15;
    // Au 5e code faux, le changement en cours est annulé : il faut le redemander.
    private static final int MAX_CODES_FAUX = 5;
    // Envois de code par compte : évite d'inonder une boîte mail.
    private static final int MAX_ENVOIS = 3;
    private static final Duration FENETRE_ENVOIS = Duration.ofMinutes(15);
    private static final Pattern FORMAT_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UtilisateursRepo utilisateursRepo;
    private final EmailService emailService;
    private final LogsServices logsServices;
    private final LimiteurTentatives limiteur;
    private final AccesCompte acces;

    // E-mail gardé tel que saisi (la connexion le cherche à l'identique), comparé sans les majuscules.
    public static String normaliser(String email) {
        return email == null || email.isBlank() ? null : email.trim();
    }

    public static boolean memeEmail(String a, String b) {
        return a != null && b != null && a.trim().equalsIgnoreCase(b.trim());
    }

    private boolean prisParUnAutre(String email, Utilisateurs u) {
        return utilisateursRepo.findByEmailIgnoreCase(email).stream().anyMatch(o -> !o.getId().equals(u.getId()));
    }

    // Appelé par updateUtilisateur quand l'e-mail saisi diffère de celui du compte.
    // Met le nouvel e-mail en attente et envoie le code ; ne sauvegarde pas (l'appelant le fait).
    public void demander(Utilisateurs u, String nouvelEmail) {
        String mail = normaliser(nouvelEmail);
        if (mail == null) return;
        if (mail.length() > 50 || !FORMAT_EMAIL.matcher(mail).matches()) {
            throw new IllegalArgumentException("L'adresse e-mail '" + nouvelEmail.trim() + "' n'est pas valide.");
        }
        if (prisParUnAutre(mail, u)) {
            throw new IllegalArgumentException("L'e-mail '" + mail + "' est déjà utilisé par un compte Cocorico "
                    + "(par exemple comme employé d'une autre ferme). Utilisez une autre adresse.");
        }
        String cleEnvois = "email-envoi:" + u.getId();
        if (limiteur.estBloque(cleEnvois, MAX_ENVOIS)) {
            throw new IllegalArgumentException("Trop de codes demandés pour ce compte. Réessayez dans "
                    + limiteur.minutesRestantes(cleEnvois, MAX_ENVOIS) + " minute(s).");
        }
        limiteur.echec(cleEnvois, MAX_ENVOIS, FENETRE_ENVOIS);

        String code = String.format("%06d", new SecureRandom().nextInt(1_000_000));
        u.setEmailEnAttente(mail);
        u.setEmailCode(code);
        u.setEmailCodeExpiry(LocalDateTime.now().plusMinutes(CODE_VALIDITE_MINUTES));
        limiteur.effacer(cleCode(u));

        boolean envoye = emailService.sendMessageCocorico(mail, u.getFullName(), "Confirmez votre adresse e-mail",
                "Votre code de confirmation Cocorico",
                "Pour utiliser cette adresse sur votre compte Cocorico, saisissez ce code :\n\n"
                        + code + "\n\n"
                        + "Ce code est valable " + CODE_VALIDITE_MINUTES + " minutes.\n\n"
                        + "Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail : aucun compte ne sera modifié.");
        if (!envoye) {
            throw new IllegalArgumentException("Le code n'a pas pu être envoyé à " + mail + ". Vérifiez l'adresse et réessayez.");
        }
    }

    // Même règle d'accès que la modification : soi-même, un ADMIN de la ferme ou un SUPER_ADMIN.
    // noRollbackFor : l'annulation au 5e code faux doit être gardée malgré l'erreur renvoyée.
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Utilisateurs confirmer(String uniqueId, String code) {
        Utilisateurs u = acces.cible(uniqueId, true);
        if (u.getEmailEnAttente() == null || u.getEmailCode() == null || u.getEmailCodeExpiry() == null) {
            throw new IllegalArgumentException("Aucun changement d'e-mail en cours. Modifiez l'e-mail pour recevoir un nouveau code.");
        }
        if (LocalDateTime.now().isAfter(u.getEmailCodeExpiry())) {
            annulerSansSauver(u);
            utilisateursRepo.save(u);
            throw new IllegalArgumentException("Le code a expiré. Modifiez à nouveau l'e-mail pour recevoir un nouveau code.");
        }
        if (code == null || !u.getEmailCode().equals(code.trim())) {
            if (limiteur.echec(cleCode(u), MAX_CODES_FAUX, Duration.ofMinutes(CODE_VALIDITE_MINUTES))) {
                annulerSansSauver(u);
                utilisateursRepo.save(u);
                throw new IllegalArgumentException("Trop de codes faux : le changement d'e-mail est annulé. Recommencez.");
            }
            throw new IllegalArgumentException("Code incorrect.");
        }
        // L'adresse a pu être prise par un autre compte depuis l'envoi du code.
        if (prisParUnAutre(u.getEmailEnAttente(), u)) {
            annulerSansSauver(u);
            utilisateursRepo.save(u);
            throw new IllegalArgumentException("Cet e-mail est maintenant utilisé par un autre compte Cocorico.");
        }

        String ancien = u.getEmail();
        String nouveau = u.getEmailEnAttente();
        u.setEmail(nouveau);
        annulerSansSauver(u);
        if (u.getInitialisation() != null) {
            u.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }
        Utilisateurs saved = utilisateursRepo.save(u);
        limiteur.effacer(cleCode(u));

        Utilisateurs auteur = acces.courant();
        logsServices.addLogs(auteur.getId(), u.getId(), "Utilisateurs", "Changement d'e-mail confirmé par code");

        if (ancien != null && !ancien.isBlank()) {
            boolean prevenu = emailService.sendMessageCocorico(ancien, u.getFullName(), "Votre e-mail a été changé",
                    "Votre e-mail Cocorico a été changé",
                    "L'adresse e-mail de votre compte Cocorico a été remplacée par " + nouveau + ".\n\n"
                            + "Si vous n'êtes pas à l'origine de ce changement, contactez-nous tout de suite "
                            + "sur WhatsApp au +223 83 91 86 99.");
            if (!prevenu) log.warn("Ancienne adresse non prévenue du changement d'e-mail (utilisateur {})", u.getId());
        }
        return saved;
    }

    @Transactional
    public Utilisateurs annuler(String uniqueId) {
        Utilisateurs u = acces.cible(uniqueId, true);
        annulerSansSauver(u);
        limiteur.effacer(cleCode(u));
        return utilisateursRepo.save(u);
    }

    private static void annulerSansSauver(Utilisateurs u) {
        u.setEmailEnAttente(null);
        u.setEmailCode(null);
        u.setEmailCodeExpiry(null);
    }

    private static String cleCode(Utilisateurs u) {
        return "email-code:" + u.getId();
    }
}
