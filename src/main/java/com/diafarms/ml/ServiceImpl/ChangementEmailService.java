package com.diafarms.ml.ServiceImpl;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.commons.AccesCompte;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.security.LimiteurTentatives;
import com.diafarms.ml.services.EmailService;
import com.diafarms.ml.services.LogsServices;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Changement d'e-mail validé par un code à 6 chiffres envoyé à la NOUVELLE adresse.
 * Tant que le code n'est pas saisi, l'ancien e-mail reste en place. Une fois le
 * changement fait, l'ancienne adresse est prévenue.
 *
 * - Utilisateur : l'e-mail sert à se connecter et à recevoir le code « mot de passe
 *   oublié » ; le changer sans preuve permettait de prendre un compte (mettre sa propre
 *   adresse puis demander un nouveau mot de passe) ou de s'en couper par une faute de frappe.
 * - Ferme : l'e-mail est affiché sur les factures et documents, et sert à Cocorico pour
 *   joindre la ferme ; le code garantit que l'adresse existe et que la ferme la lit.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChangementEmailService {

    private static final int CODE_VALIDITE_MINUTES = 15;
    // Au 5e code faux, le changement en cours est annulé : il faut le redemander.
    private static final int MAX_CODES_FAUX = 5;
    // Envois de code par compte ou par ferme : évite d'inonder une boîte mail.
    private static final int MAX_ENVOIS = 3;
    private static final Duration FENETRE_ENVOIS = Duration.ofMinutes(15);
    private static final Pattern FORMAT_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final UtilisateursRepo utilisateursRepo;
    private final FarmsRepo farmsRepo;
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

    // Vrai si un code pour cet e-mail est déjà en attente et encore valable : ré-enregistrer
    // le même formulaire ne renvoie pas de code.
    public static boolean dejaEnAttente(String email, String enAttente, LocalDateTime expiry) {
        return memeEmail(email, enAttente) && expiry != null && LocalDateTime.now().isBefore(expiry);
    }

    // ------------------------------------------------------------------ utilisateur

    // Appelé par updateUtilisateur quand l'e-mail saisi diffère de celui du compte.
    // Met le nouvel e-mail en attente et envoie le code ; ne sauvegarde pas (l'appelant le fait).
    public void demander(Utilisateurs u, String nouvelEmail) {
        String mail = verifierFormat(nouvelEmail, 50);
        if (mail == null) return;
        if (prisParUnAutre(mail, u)) {
            throw new IllegalArgumentException("L'e-mail '" + mail + "' est déjà utilisé par un compte Cocorico "
                    + "(par exemple comme employé d'une autre ferme). Utilisez une autre adresse.");
        }
        compterEnvoi("email-envoi:" + u.getId(), "ce compte");
        String code = nouveauCode();
        u.setEmailEnAttente(mail);
        u.setEmailCode(code);
        u.setEmailCodeExpiry(LocalDateTime.now().plusMinutes(CODE_VALIDITE_MINUTES));
        limiteur.effacer(cleCode(u));
        envoyerCode(mail, u.getFullName(), code, "sur votre compte Cocorico", "aucun compte ne sera modifié");
    }

    // Même règle d'accès que la modification : soi-même, un ADMIN de la ferme ou un SUPER_ADMIN.
    // noRollbackFor : l'annulation au 5e code faux doit être gardée malgré l'erreur renvoyée.
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Utilisateurs confirmer(String uniqueId, String code) {
        Utilisateurs u = acces.cible(uniqueId, true);
        String erreur = verifierCode(u.getEmailEnAttente(), u.getEmailCode(), u.getEmailCodeExpiry(), code, cleCode(u));
        if (erreur == null && prisParUnAutre(u.getEmailEnAttente(), u)) {
            // L'adresse a pu être prise par un autre compte depuis l'envoi du code.
            erreur = "Cet e-mail est maintenant utilisé par un autre compte Cocorico.";
        }
        if (erreur != null) {
            if (!erreur.equals(CODE_INCORRECT)) {
                annulerSansSauver(u);
                utilisateursRepo.save(u);
            }
            throw new IllegalArgumentException(erreur);
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
        logsServices.addLogs(acces.courant().getId(), u.getId(), "Utilisateurs", "Changement d'e-mail confirmé par code");
        prevenirAncienne(ancien, u.getFullName(), "de votre compte Cocorico", nouveau);
        return saved;
    }

    @Transactional
    public Utilisateurs annuler(String uniqueId) {
        Utilisateurs u = acces.cible(uniqueId, true);
        annulerSansSauver(u);
        limiteur.effacer(cleCode(u));
        return utilisateursRepo.save(u);
    }

    // ------------------------------------------------------------------ ferme

    // Appelé par FarmController.setMyFarmIdentite quand l'e-mail saisi diffère de celui de la
    // ferme. Met le nouvel e-mail en attente et envoie le code ; ne sauvegarde pas.
    public void demanderFerme(Farm farm, String nouvelEmail) {
        String mail = verifierFormat(nouvelEmail, 100);
        if (mail == null) return;
        compterEnvoi("email-envoi-ferme:" + farm.getId(), "cette ferme");
        String code = nouveauCode();
        farm.setEmailEnAttente(mail);
        farm.setEmailCode(code);
        farm.setEmailCodeExpiry(LocalDateTime.now().plusMinutes(CODE_VALIDITE_MINUTES));
        limiteur.effacer(cleCode(farm));
        envoyerCode(mail, nomFerme(farm), code, "comme e-mail de la ferme " + nomFerme(farm) + " sur Cocorico",
                "la ferme ne sera pas modifiée");
    }

    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public Farm confirmerFerme(Farm farm, String code) {
        String erreur = verifierCode(farm.getEmailEnAttente(), farm.getEmailCode(), farm.getEmailCodeExpiry(), code, cleCode(farm));
        if (erreur != null) {
            if (!erreur.equals(CODE_INCORRECT)) {
                annulerSansSauver(farm);
                farmsRepo.save(farm);
            }
            throw new IllegalArgumentException(erreur);
        }
        String ancien = farm.getEmail();
        String nouveau = farm.getEmailEnAttente();
        farm.setEmail(nouveau);
        annulerSansSauver(farm);
        Farm saved = farmsRepo.save(farm);
        limiteur.effacer(cleCode(farm));
        Utilisateurs auteur = acces.courant();
        logsServices.addLogs(auteur.getId(), auteur.getId(), "Farm", "E-mail de la ferme changé (confirmé par code)");
        prevenirAncienne(ancien, nomFerme(farm), "de la ferme " + nomFerme(farm), nouveau);
        return saved;
    }

    @Transactional
    public Farm annulerFerme(Farm farm) {
        annulerSansSauver(farm);
        limiteur.effacer(cleCode(farm));
        return farmsRepo.save(farm);
    }

    // ------------------------------------------------------------------ commun

    private static final String CODE_INCORRECT = "Code incorrect.";

    private String verifierFormat(String email, int longueurMax) {
        String mail = normaliser(email);
        if (mail == null) return null;
        if (mail.length() > longueurMax || !FORMAT_EMAIL.matcher(mail).matches()) {
            throw new IllegalArgumentException("L'adresse e-mail '" + mail + "' n'est pas valide.");
        }
        return mail;
    }

    private boolean prisParUnAutre(String email, Utilisateurs u) {
        return utilisateursRepo.findByEmailIgnoreCase(email).stream().anyMatch(o -> !o.getId().equals(u.getId()));
    }

    private void compterEnvoi(String cle, String pourQui) {
        if (limiteur.estBloque(cle, MAX_ENVOIS)) {
            throw new IllegalArgumentException("Trop de codes demandés pour " + pourQui + ". Réessayez dans "
                    + limiteur.minutesRestantes(cle, MAX_ENVOIS) + " minute(s).");
        }
        limiteur.echec(cle, MAX_ENVOIS, FENETRE_ENVOIS);
    }

    private static String nouveauCode() {
        return String.format("%06d", new SecureRandom().nextInt(1_000_000));
    }

    private void envoyerCode(String mail, String nom, String code, String usage, String sinon) {
        boolean envoye = emailService.sendMessageCocorico(mail, nom, "Confirmez votre adresse e-mail",
                "Votre code de confirmation Cocorico",
                "Pour utiliser cette adresse " + usage + ", saisissez ce code :\n\n"
                        + code + "\n\n"
                        + "Ce code est valable " + CODE_VALIDITE_MINUTES + " minutes.\n\n"
                        + "Si vous n'êtes pas à l'origine de cette demande, ignorez cet e-mail : " + sinon + ".");
        if (!envoye) {
            throw new IllegalArgumentException("Le code n'a pas pu être envoyé à " + mail + ". Vérifiez l'adresse et réessayez.");
        }
    }

    // null si le code est bon ; sinon le message d'erreur. Tout message autre que
    // CODE_INCORRECT signifie que le changement en cours doit être annulé.
    private String verifierCode(String enAttente, String codeAttendu, LocalDateTime expiry, String code, String cle) {
        if (enAttente == null || codeAttendu == null || expiry == null) {
            return "Aucun changement d'e-mail en cours. Modifiez l'e-mail pour recevoir un nouveau code.";
        }
        if (LocalDateTime.now().isAfter(expiry)) {
            return "Le code a expiré. Modifiez à nouveau l'e-mail pour recevoir un nouveau code.";
        }
        if (code == null || !codeAttendu.equals(code.trim())) {
            if (limiteur.echec(cle, MAX_CODES_FAUX, Duration.ofMinutes(CODE_VALIDITE_MINUTES))) {
                return "Trop de codes faux : le changement d'e-mail est annulé. Recommencez.";
            }
            return CODE_INCORRECT;
        }
        return null;
    }

    private void prevenirAncienne(String ancien, String nom, String de, String nouveau) {
        if (ancien == null || ancien.isBlank()) return;
        boolean prevenu = emailService.sendMessageCocorico(ancien, nom, "Votre e-mail a été changé",
                "Votre e-mail Cocorico a été changé",
                "L'adresse e-mail " + de + " a été remplacée par " + nouveau + ".\n\n"
                        + "Si vous n'êtes pas à l'origine de ce changement, contactez-nous tout de suite "
                        + "sur WhatsApp au +223 83 91 86 99.");
        if (!prevenu) log.warn("Ancienne adresse non prévenue du changement d'e-mail ({})", de);
    }

    private static String nomFerme(Farm farm) {
        return farm.getNom() == null || farm.getNom().isBlank() ? "votre ferme" : farm.getNom();
    }

    private static void annulerSansSauver(Utilisateurs u) {
        u.setEmailEnAttente(null);
        u.setEmailCode(null);
        u.setEmailCodeExpiry(null);
    }

    private static void annulerSansSauver(Farm f) {
        f.setEmailEnAttente(null);
        f.setEmailCode(null);
        f.setEmailCodeExpiry(null);
    }

    private static String cleCode(Utilisateurs u) {
        return "email-code:" + u.getId();
    }

    private static String cleCode(Farm f) {
        return "email-code-ferme:" + f.getId();
    }
}
