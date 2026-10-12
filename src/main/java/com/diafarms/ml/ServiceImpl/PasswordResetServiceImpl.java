package com.diafarms.ml.ServiceImpl;

import java.security.SecureRandom;
import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.security.LimiteurTentatives;
import com.diafarms.ml.services.EmailService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.PasswordResetService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final int CODE_VALIDITY_MINUTES = 5;
    // Au 5e code faux, le code en cours est annulé : il faut en redemander un.
    private static final int MAX_CODES_FAUX = 5;

    private final UtilisateursRepo utilisateursRepo;
    private final PasswordEncoder encoder;
    private final EmailService emailService;
    private final LogsServices logsServices;
    private final LimiteurTentatives limiteur;

    @Override
    @Transactional
    public void requestReset(String email) {
        if (email == null || email.isBlank()) return;

        Utilisateurs user = utilisateursRepo.findByEmail(email.trim());
        if (user == null) return; // silencieux : pas d'énumération d'emails

        SecureRandom random = new SecureRandom();
        String code = String.format("%06d", random.nextInt(1_000_000));

        user.setResetPasswordCode(code);
        user.setResetPasswordCodeExpiry(LocalDateTime.now().plusMinutes(CODE_VALIDITY_MINUTES));
        utilisateursRepo.save(user);
        limiteur.effacer(cleCode(user));

        emailService.sendPasswordResetCode(user.getEmail(), user.getFullName(), code);
    }

    @Override
    @Transactional
    public boolean verifyCode(String email, String code) {
        if (email == null || code == null) return false;
        Utilisateurs user = utilisateursRepo.findByEmail(email.trim());
        return verifierEtCompter(user, code);
    }

    @Override
    // noRollbackFor : l'effacement du code au 5e essai faux doit être gardé même si
    // la méthode finit en erreur « Code invalide ».
    @Transactional(noRollbackFor = IllegalArgumentException.class)
    public void resetPassword(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Le nouveau mot de passe doit contenir au moins 8 caractères.");
        }

        Utilisateurs user = utilisateursRepo.findByEmail(email == null ? "" : email.trim());
        if (!verifierEtCompter(user, code)) {
            throw new IllegalArgumentException("Code invalide ou expiré.");
        }

        user.setPassword(encoder.encode(newPassword));
        user.setMustChangePassword(false);
        // Usage unique : le code ne doit plus pouvoir resservir une fois consommé.
        user.setResetPasswordCode(null);
        user.setResetPasswordCodeExpiry(null);
        limiteur.effacer(cleCode(user));
        if (user.getInitialisation() != null) {
            user.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }
        utilisateursRepo.save(user);

        logsServices.addLogs(user.getId(), user.getId(), "Utilisateurs", "Réinitialisation du mot de passe via code email");
    }

    private static String cleCode(Utilisateurs user) {
        return "code:" + user.getId();
    }

    // Vérifie le code et compte les erreurs : au MAX_CODES_FAUX-ième code faux, le code
    // est effacé, ce qui rend impossible de deviner un code en essayant tous les chiffres.
    private boolean verifierEtCompter(Utilisateurs user, String code) {
        if (isCodeValid(user, code)) return true;
        if (user != null && user.getResetPasswordCode() != null
                && limiteur.echec(cleCode(user), MAX_CODES_FAUX, java.time.Duration.ofMinutes(CODE_VALIDITY_MINUTES))) {
            user.setResetPasswordCode(null);
            user.setResetPasswordCodeExpiry(null);
            utilisateursRepo.save(user);
            limiteur.effacer(cleCode(user));
        }
        return false;
    }

    private boolean isCodeValid(Utilisateurs user, String code) {
        if (user == null || code == null) return false;
        if (user.getResetPasswordCode() == null || user.getResetPasswordCodeExpiry() == null) return false;
        if (!user.getResetPasswordCode().equals(code.trim())) return false;
        return LocalDateTime.now().isBefore(user.getResetPasswordCodeExpiry());
    }
}
