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

    public String generateAndEncryptQRCode(String username, String uniqueId, String fullName,
                                          String rolesPipe, Instant expiresAt, Instant now) {

        // 1. JWT Token génération — le subject DOIT être le username, pas le uniqueId :
        // OtherService.getCurrentUser() (utilisé par la quasi-totalité des endpoints, dont
        // /projets/select) résout l'utilisateur via findByUsername(jwt.getSubject()). Avec
        // uniqueId en subject, cette recherche échouait silencieusement (retour null) pour
        // toute requête authentifiée par un token issu d'un QR, d'où un utilisateur "connecté"
        // mais sans aucune donnée (projets, etc.) alors que le token JWT lui-même est valide.
        // scope (espaces, pas pipes) plutôt que role : c'est le nom de claim que
        // JwtGrantedAuthoritiesConverter lit par défaut pour peupler les authorities.
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(username)
                .issuedAt(now)
                .expiresAt(expiresAt)
                .issuer("diafarms-qr")
                .claim("type", "QR_CODE")
                .claim("uniqueId", uniqueId)
                .claim("fullName", fullName)
                .claim("role", rolesPipe)
                .claim("scope", rolesPipe == null ? "" : rolesPipe.replace("|", " "))
                .build();

        String token = jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();

        // 2. Objet d'échange chiffré : volontairement minimal (voir QrCodeEncrypte) pour
        // que le QR reste scannable — fullName/role restent disponibles via les claims du JWT.
        QrCodeEncrypte qrCode = QrCodeEncrypte.builder()
                .qrExpiresAt(expiresAt.getEpochSecond() > now.plus(36500, ChronoUnit.DAYS).getEpochSecond()
                        ? null
                        : LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()))
                .uniqueIdUser(uniqueId)
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