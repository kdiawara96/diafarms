package com.diafarms.ml.config;


import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.QrCodeEncrypte;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class QRCodeService {

    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final UtilisateursRepo utilisateursRepo;
    private final AESService aesService;

    public String generateAndEncryptQRCode(String uniqueId, String fullName, 
                                          String rolesPipe, Instant expiresAt, Instant now) {
        
        // 1. JWT Token génération
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(uniqueId)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .issuer("diafarms-qr")
                .claim("type", "QR_CODE")
                .claim("uniqueId", uniqueId)
                .claim("fullName", fullName)
                .claim("role", rolesPipe)
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        // 2. Objet d'échange chiffré
        QrCodeEncrypte qrCode = QrCodeEncrypte.builder()
                .qrGeneratedAt(LocalDateTime.ofInstant(now, ZoneId.systemDefault()))
                .qrExpiresAt(expiresAt.getEpochSecond() > now.plus(36500, ChronoUnit.DAYS).getEpochSecond() 
                        ? null 
                        : LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()))
                .role(rolesPipe)
                .uniqueIdUser(uniqueId)
                .fullNameUser(fullName)
                .token(token)
                .build();

        try {
            return aesService.encryptObject(qrCode);
        } catch (Exception e) {
            // 🔴 ICI : Cela va afficher toute la trace rouge dans la console de ton IDE (IntelliJ/Eclipse)
            e.printStackTrace();
            // Et on renvoie le vrai message d'erreur au front pour comprendre
            throw new RuntimeException("Détail de l'erreur : " + e.getMessage(), e);
        }
    }

    public QrCodeEncrypte decryptAndValidate(String encryptedQr) {
        try {
            QrCodeEncrypte qrCode = aesService.decryptObject(encryptedQr, QrCodeEncrypte.class);
            
            // Validation automatique de la signature et de l'expiration du JWT
            Jwt jwt = jwtDecoder.decode(qrCode.getToken());
            String uniqueId = jwt.getClaimAsString("uniqueId");
            
            Utilisateurs user = utilisateursRepo.findByUniqueId(uniqueId)
                    .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

            // Logique de révocation : le jeton en BDD doit matcher le jeton scanné
            if (user.getInfoQrcodeEncrypte() == null || !user.getInfoQrcodeEncrypte().equals(encryptedQr)) {
                throw new RuntimeException("Ce QR Code n'est plus actif ou a été révoqué");
            }
            
            return qrCode;
        } catch (Exception e) {
            throw new RuntimeException("QR code invalide, expiré ou révoqué");
        }
    }
}