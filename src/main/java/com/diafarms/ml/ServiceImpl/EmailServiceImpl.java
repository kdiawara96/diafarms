package com.diafarms.ml.ServiceImpl;

import java.time.LocalDate;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.diafarms.ml.services.EmailService;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j
public class EmailServiceImpl implements EmailService {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Override
    public boolean sendWelcomeEmail(String to, String fullName, String username, String password) {
        try {
            // multipart=true + setText(plain, html) : fournit une alternative texte
            // brut en plus du HTML. Les emails HTML-only depuis un compte Gmail
            // personnel (pas un domaine authentifié SPF/DKIM dédié) sont beaucoup
            // plus souvent classés comme spam par les filtres.
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, "DiaFarms");
            helper.setTo(to);
            helper.setReplyTo(fromAddress);
            helper.setSubject("Vos identifiants DiaFarms");
            helper.setText(buildPlainTextBody(fullName, username, password), buildHtmlBody(fullName, username, password));
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Échec de l'envoi de l'email de bienvenue à {} : {}", to, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendPasswordResetCode(String to, String fullName, String code) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, "DiaFarms");
            helper.setTo(to);
            helper.setReplyTo(fromAddress);
            helper.setSubject("Votre code de réinitialisation DiaFarms");
            helper.setText(buildResetPlainTextBody(fullName, code), buildResetHtmlBody(fullName, code));
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Échec de l'envoi du code de réinitialisation à {} : {}", to, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendPasswordResetByAdmin(String to, String fullName, String username, String newPassword) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, "DiaFarms");
            helper.setTo(to);
            helper.setReplyTo(fromAddress);
            helper.setSubject("Votre mot de passe DiaFarms a été réinitialisé");
            helper.setText(buildAdminResetPlainTextBody(fullName, username, newPassword),
                    buildAdminResetHtmlBody(fullName, username, newPassword));
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Échec de l'envoi du mot de passe réinitialisé à {} : {}", to, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendAbonnementAValider(String to, String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, "DiaFarms");
            helper.setTo(to);
            helper.setReplyTo(fromAddress);
            helper.setSubject("Abonnement à valider — " + farmNom);
            helper.setText(
                    buildAbonnementAValiderPlainTextBody(farmNom, montant, periodicite, moyenPaiement, reference),
                    buildAbonnementAValiderHtmlBody(farmNom, montant, periodicite, moyenPaiement, reference));
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Échec de l'envoi de l'email 'abonnement à valider' à {} : {}", to, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean sendAbonnementValide(String to, String fullName, String farmNom, LocalDate dateFin) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromAddress, "DiaFarms");
            helper.setTo(to);
            helper.setReplyTo(fromAddress);
            helper.setSubject("Votre abonnement DiaFarms est activé");
            helper.setText(
                    buildAbonnementValidePlainTextBody(fullName, farmNom, dateFin),
                    buildAbonnementValideHtmlBody(fullName, farmNom, dateFin));
            mailSender.send(message);
            return true;
        } catch (Exception e) {
            log.error("Échec de l'envoi de l'email 'abonnement validé' à {} : {}", to, e.getMessage());
            return false;
        }
    }

    private String buildAdminResetPlainTextBody(String fullName, String username, String newPassword) {
        return """
            Bonjour %s,

            Un administrateur a réinitialisé votre mot de passe DiaFarms. Voici vos nouveaux identifiants :

            Identifiant : %s
            Nouveau mot de passe temporaire : %s

            Pour votre sécurité, un changement de mot de passe vous sera demandé dès votre prochaine connexion.

            Si vous n'êtes pas à l'origine de cette demande, contactez votre administrateur.

            L'équipe DiaFarms
            """.formatted(fullName, username, newPassword);
    }

    private String buildAdminResetHtmlBody(String fullName, String username, String newPassword) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #1f2937;">
              <h2 style="color: #15803d;">Mot de passe réinitialisé</h2>
              <p>Bonjour %s,</p>
              <p>Un administrateur a réinitialisé votre mot de passe DiaFarms. Voici vos nouveaux identifiants :</p>
              <div style="background: #f3f4f6; border-radius: 8px; padding: 16px; margin: 16px 0;">
                <p style="margin: 4px 0;"><strong>Identifiant :</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Nouveau mot de passe temporaire :</strong> %s</p>
              </div>
              <p>Pour votre sécurité, un changement de mot de passe vous sera demandé dès votre prochaine connexion.</p>
              <p style="color: #6b7280; font-size: 13px; margin-top: 24px;">Si vous n'êtes pas à l'origine de cette demande, contactez votre administrateur.</p>
              <p>L'équipe DiaFarms</p>
            </div>
            """.formatted(fullName, username, newPassword);
    }

    private String buildResetPlainTextBody(String fullName, String code) {
        return """
            Bonjour %s,

            Voici votre code de réinitialisation de mot de passe : %s

            Ce code est valable 5 minutes.

            Si vous n'êtes pas à l'origine de cette demande, ignorez cet email : votre mot de passe restera inchangé.

            L'équipe DiaFarms
            """.formatted(fullName, code);
    }

    private String buildResetHtmlBody(String fullName, String code) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #1f2937;">
              <h2 style="color: #15803d;">Réinitialisation de mot de passe</h2>
              <p>Bonjour %s,</p>
              <p>Voici votre code de vérification :</p>
              <div style="background: #f3f4f6; border-radius: 8px; padding: 16px; margin: 16px 0; text-align: center;">
                <span style="font-size: 28px; font-weight: bold; letter-spacing: 4px; color: #15803d;">%s</span>
              </div>
              <p>Ce code est valable <strong>5 minutes</strong>.</p>
              <p style="color: #6b7280; font-size: 13px; margin-top: 24px;">Si vous n'êtes pas à l'origine de cette demande, ignorez cet email : votre mot de passe restera inchangé.</p>
              <p>L'équipe DiaFarms</p>
            </div>
            """.formatted(fullName, code);
    }

    private String buildPlainTextBody(String fullName, String username, String password) {
        return """
            Bonjour %s,

            Votre espace DiaFarms a été créé avec succès. Voici vos identifiants de connexion :

            Identifiant : %s
            Mot de passe temporaire : %s

            Pour votre sécurité, un changement de mot de passe vous sera demandé dès votre première connexion.

            Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.

            L'équipe DiaFarms
            """.formatted(fullName, username, password);
    }

    private String buildHtmlBody(String fullName, String username, String password) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #1f2937;">
              <h2 style="color: #15803d;">Bienvenue sur DiaFarms</h2>
              <p>Bonjour %s,</p>
              <p>Votre espace DiaFarms a été créé avec succès. Voici vos identifiants de connexion :</p>
              <div style="background: #f3f4f6; border-radius: 8px; padding: 16px; margin: 16px 0;">
                <p style="margin: 4px 0;"><strong>Identifiant :</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Mot de passe temporaire :</strong> %s</p>
              </div>
              <p>Pour votre sécurité, un changement de mot de passe vous sera demandé dès votre première connexion.</p>
              <p style="color: #6b7280; font-size: 13px; margin-top: 24px;">Si vous n'êtes pas à l'origine de cette demande, ignorez cet email.</p>
              <p>L'équipe DiaFarms</p>
            </div>
            """.formatted(fullName, username, password);
    }

    private String buildAbonnementAValiderPlainTextBody(String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference) {
        return """
            Bonjour,

            La ferme %s a déclaré avoir payé son abonnement DiaFarms.

            Montant : %.0f FCFA
            Périodicité : %s
            Moyen de paiement : %s
            Référence : %s

            Connecte-toi à ton portail SUPER_ADMIN pour vérifier le paiement et valider.

            L'équipe DiaFarms
            """.formatted(farmNom, montant, periodicite, moyenPaiement,
                    (reference == null || reference.isBlank()) ? "—" : reference);
    }

    private String buildAbonnementAValiderHtmlBody(String farmNom, Double montant,
            String periodicite, String moyenPaiement, String reference) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #1f2937;">
              <h2 style="color: #15803d;">Abonnement à valider</h2>
              <p>La ferme <strong>%s</strong> a déclaré avoir payé son abonnement DiaFarms.</p>
              <div style="background: #f3f4f6; border-radius: 8px; padding: 16px; margin: 16px 0;">
                <p style="margin: 4px 0;"><strong>Montant :</strong> %.0f FCFA</p>
                <p style="margin: 4px 0;"><strong>Périodicité :</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Moyen de paiement :</strong> %s</p>
                <p style="margin: 4px 0;"><strong>Référence :</strong> %s</p>
              </div>
              <p>Connecte-toi à ton portail SUPER_ADMIN pour vérifier le paiement et valider.</p>
              <p>L'équipe DiaFarms</p>
            </div>
            """.formatted(farmNom, montant, periodicite, moyenPaiement,
                    (reference == null || reference.isBlank()) ? "—" : reference);
    }

    private String buildAbonnementValidePlainTextBody(String fullName, String farmNom, LocalDate dateFin) {
        return """
            Bonjour %s,

            Le paiement de l'abonnement DiaFarms de %s a été validé.

            Votre abonnement est actif jusqu'au %s.

            Merci de votre confiance.

            L'équipe DiaFarms
            """.formatted(fullName, farmNom, dateFin);
    }

    private String buildAbonnementValideHtmlBody(String fullName, String farmNom, LocalDate dateFin) {
        return """
            <div style="font-family: Arial, sans-serif; max-width: 480px; margin: auto; color: #1f2937;">
              <h2 style="color: #15803d;">Abonnement activé</h2>
              <p>Bonjour %s,</p>
              <p>Le paiement de l'abonnement DiaFarms de <strong>%s</strong> a été validé.</p>
              <div style="background: #f3f4f6; border-radius: 8px; padding: 16px; margin: 16px 0;">
                <p style="margin: 4px 0;">Abonnement actif jusqu'au <strong>%s</strong>.</p>
              </div>
              <p>Merci de votre confiance.</p>
              <p>L'équipe DiaFarms</p>
            </div>
            """.formatted(fullName, farmNom, dateFin);
    }
}
