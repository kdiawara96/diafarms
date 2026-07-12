package com.diafarms.ml.DTO;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import java.time.LocalDateTime;

// Contenu encodé dans le QR : on le garde volontairement minimal. Un contenu chiffré
// trop long force un QR avec beaucoup de modules, illisible par une caméra de téléphone
// (c'est ce qui rendait le scan impossible). role/fullNameUser/qrGeneratedAt sont retirés
// car déjà dupliqués (le token JWT porte déjà uniqueId/fullName/role, et qrGeneratedAt
// n'était lu nulle part — voir Utilisateurs.qrGeneratedAt côté DB si besoin d'historique).
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class QrCodeEncrypte {
    // Conservée uniquement pour que le mobile puisse détecter une expiration côté client
    // sans appel réseau (le serveur, lui, revalide toujours via l'expiration du JWT).
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime qrExpiresAt;

    private String uniqueIdUser;
    private String token;
}