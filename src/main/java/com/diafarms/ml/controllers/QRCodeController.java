package com.diafarms.ml.controllers;


import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.QRCodeRequestDTO;
import com.diafarms.ml.DTO.QrCodeEncrypte;
import com.diafarms.ml.config.QRCodeService;
import com.diafarms.ml.enums.TokenDuration;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.repository.UtilisateursRepo;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/diafarms/api/v1/qrcode")
@RequiredArgsConstructor
public class QRCodeController {

    private final QRCodeService qrCodeService;
    private final UtilisateursRepo utilisateursRepo;

    @PostMapping("/generate")
    public ResponseEntity<ApiResponse<Map<String, String>>> generateQRCode(@RequestBody QRCodeRequestDTO request) {
        try {
            Utilisateurs user = utilisateursRepo.findByUniqueId(request.getUniqueId())
                    .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable"));

            String rolesPipe = user.getRoles().stream()
                    .map(r -> r.getRole()) 
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("|"));

            TokenDuration duration = TokenDuration.fromValue(request.getDuration());
            Instant now = Instant.now();
            Instant expiresAt = duration.calculateExpiry(now);

            String encryptedQr = qrCodeService.generateAndEncryptQRCode(
                    user.getUniqueId(),
                    user.getFullName(),
                    rolesPipe,
                    expiresAt,
                    now
            );

            // Hydratation conforme à ton modèle de données
            user.setQrGeneratedAt(LocalDateTime.ofInstant(now, ZoneId.systemDefault()));
            user.setQrExpiresAt(duration.isPermanent() ? null : LocalDateTime.ofInstant(expiresAt, ZoneId.systemDefault()));
            user.setInfoQrcodeEncrypte(encryptedQr); 
            
            utilisateursRepo.save(user);

            Map<String, String> result = Map.of("encryptedQr", encryptedQr);
            return ApiResponse.createResponse("QR Code généré avec succès", HttpStatus.OK, result, null);

        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/scan")
    public ResponseEntity<ApiResponse<Map<String, Object>>> scanQRCode(@RequestBody Map<String, String> body) {
        try {
            String encryptedQr = body.get("encryptedQr");
            if (encryptedQr == null) {
                return ApiResponse.createResponse("Payload absent", HttpStatus.BAD_REQUEST, null, List.of("Le paramètre encryptedQr est requis"));
            }

            QrCodeEncrypte qrCode = qrCodeService.decryptAndValidate(encryptedQr);
            
            Map<String, Object> result = Map.of(
                "valid", true,
                "uniqueId", qrCode.getUniqueIdUser(),
                "fullName", qrCode.getFullNameUser(),
                "role", qrCode.getRole()
            );
            
            return ApiResponse.createResponse("QR Code scanné et validé avec succès", HttpStatus.OK, result, null);

        } catch (RuntimeException e) {
            // Capturera les erreurs de révocation ou d'expiration de QRCodeService
            return ApiResponse.createResponse("Accès refusé", HttpStatus.UNAUTHORIZED, Map.of("valid", false), List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

   @PostMapping("/revoke/{uniqueId}")
    public ResponseEntity<ApiResponse<Map<String, Object>>> revokeQRCode(@PathVariable String uniqueId) { // 👈 Changé String en Object ici
        try {
            Utilisateurs user = utilisateursRepo.findByUniqueId(uniqueId)
                    .orElseThrow(() -> new IllegalArgumentException("Utilisateur introuvable"));
            
            user.setInfoQrcodeEncrypte(null);
            user.setQrExpiresAt(null);
            utilisateursRepo.save(user);
            
            // 🟢 On passe le message + un statut clair sous forme de map
            Map<String, Object> result = Map.of(
                "message", "Le jeton mobile a été invalidé.",
                "revoked", true
            );
            return ApiResponse.createResponse("QR Code révoqué avec succès", HttpStatus.OK, result, null);

        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}