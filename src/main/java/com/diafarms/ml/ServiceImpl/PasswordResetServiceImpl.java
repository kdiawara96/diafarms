package com.diafarms.ml.ServiceImpl;

import java.security.SecureRandom;
import java.time.LocalDateTime;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.services.EmailService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.PasswordResetService;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final int CODE_VALIDITY_MINUTES = 5;

    private final UtilisateursRepo utilisateursRepo;
    private final PasswordEncoder encoder;
    private final EmailService emailService;
    private final LogsServices logsServices;

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

        emailService.sendPasswordResetCode(user.getEmail(), user.getFullName(), code);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean verifyCode(String email, String code) {
        if (email == null || code == null) return false;
        Utilisateurs user = utilisateursRepo.findByEmail(email.trim());
        return isCodeValid(user, code);
    }

    @Override
    @Transactional
    public void resetPassword(String email, String code, String newPassword) {
        if (newPassword == null || newPassword.length() < 8) {
            throw new IllegalArgumentException("Le nouveau mot de passe doit contenir au moins 8 caractères.");
        }

        Utilisateurs user = utilisateursRepo.findByEmail(email == null ? "" : email.trim());
        if (!isCodeValid(user, code)) {
            throw new IllegalArgumentException("Code invalide ou expiré.");
        }

        user.setPassword(encoder.encode(newPassword));
        user.setMustChangePassword(false);
        // Usage unique : le code ne doit plus pouvoir resservir une fois consommé.
        user.setResetPasswordCode(null);
        user.setResetPasswordCodeExpiry(null);
        if (user.getInitialisation() != null) {
            user.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }
        utilisateursRepo.save(user);

        logsServices.addLogs(user.getId(), user.getId(), "Utilisateurs", "Réinitialisation du mot de passe via code email");
    }

    private boolean isCodeValid(Utilisateurs user, String code) {
        if (user == null || code == null) return false;
        if (user.getResetPasswordCode() == null || user.getResetPasswordCodeExpiry() == null) return false;
        if (!user.getResetPasswordCode().equals(code.trim())) return false;
        return LocalDateTime.now().isBefore(user.getResetPasswordCodeExpiry());
    }
}
