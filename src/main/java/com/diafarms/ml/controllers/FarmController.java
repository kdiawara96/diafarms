package com.diafarms.ml.controllers;

import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.services.MinioService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Endpoints pour la ferme courante — ville (WeatherService) et identité visuelle
 * (logo/tampon, insérés sur les factures et bulletins de salaire générés en PDF,
 * voir FactureServiceImpl/SalaireServiceImpl). Pas encore de gestion complète de la
 * ferme (nom, adresse...), à étendre si le besoin apparaît.
 */
@RestController
@RequestMapping("/diafarms/api/v1/farms")
@RequiredArgsConstructor
public class FarmController {

    private final FarmsRepo farmsRepo;
    private final OtherService otherService;
    private final MinioService minioService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, String>>> getMyFarm() {
        Farm farm = getCurrentUserFarm();
        if (farm == null) {
            return ApiResponse.createResponse("Aucune ferme associée à ce compte", HttpStatus.OK, null, null);
        }
        Map<String, String> result = new HashMap<>();
        result.put("uniqueId", farm.getUniqueId());
        result.put("nom", farm.getNom() == null ? "" : farm.getNom());
        result.put("quartier", farm.getQuartier() == null ? "" : farm.getQuartier());
        result.put("ville", farm.getVille() == null ? "" : farm.getVille());
        result.put("pays", farm.getPays() == null ? "" : farm.getPays());
        result.put("telephone1", farm.getTelephone1() == null ? "" : farm.getTelephone1());
        result.put("telephone2", farm.getTelephone2() == null ? "" : farm.getTelephone2());
        result.put("email", farm.getEmail() == null ? "" : farm.getEmail());
        result.put("logoUrl", presignedUrlOrNull(farm.getLogoNomMinio()));
        result.put("tamponUrl", presignedUrlOrNull(farm.getTamponNomMinio()));
        return ApiResponse.createResponse("Ferme récupérée", HttpStatus.OK, result, null);
    }

    // Coordonnées de la ferme (nom, adresse, contact) — un seul formulaire côté web
    // (Paramètres > Identité de la ferme), un seul endpoint ici plutôt qu'un par champ.
    // Chaîne vide/blanche => retiré (null), pas conservé tel quel.
    @PutMapping("/me/identite")
    public ResponseEntity<ApiResponse<Map<String, String>>> setMyFarmIdentite(@RequestBody Map<String, String> body) {
        Farm farm = getCurrentUserFarm();
        if (farm == null) {
            return ApiResponse.createResponse("Aucune ferme associée à ce compte", HttpStatus.BAD_REQUEST, null, null);
        }
        farm.setNom(blankToNull(body.get("nom")));
        farm.setQuartier(blankToNull(body.get("quartier")));
        farm.setVille(blankToNull(body.get("ville")));
        farm.setPays(blankToNull(body.get("pays")));
        farm.setTelephone1(blankToNull(body.get("telephone1")));
        farm.setTelephone2(blankToNull(body.get("telephone2")));
        farm.setEmail(blankToNull(body.get("email")));
        farmsRepo.save(farm);

        Map<String, String> result = new HashMap<>();
        result.put("nom", farm.getNom() == null ? "" : farm.getNom());
        result.put("quartier", farm.getQuartier() == null ? "" : farm.getQuartier());
        result.put("ville", farm.getVille() == null ? "" : farm.getVille());
        result.put("pays", farm.getPays() == null ? "" : farm.getPays());
        result.put("telephone1", farm.getTelephone1() == null ? "" : farm.getTelephone1());
        result.put("telephone2", farm.getTelephone2() == null ? "" : farm.getTelephone2());
        result.put("email", farm.getEmail() == null ? "" : farm.getEmail());
        return ApiResponse.createResponse("Coordonnées de la ferme mises à jour", HttpStatus.OK, result, null);
    }

    private String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @PostMapping("/me/logo")
    public ResponseEntity<ApiResponse<String>> uploadLogo(@RequestParam("file") MultipartFile file) {
        return uploadBranding(file, true);
    }

    @DeleteMapping("/me/logo")
    public ResponseEntity<ApiResponse<String>> removeLogo() {
        return removeBranding(true);
    }

    @PostMapping("/me/tampon")
    public ResponseEntity<ApiResponse<String>> uploadTampon(@RequestParam("file") MultipartFile file) {
        return uploadBranding(file, false);
    }

    @DeleteMapping("/me/tampon")
    public ResponseEntity<ApiResponse<String>> removeTampon() {
        return removeBranding(false);
    }

    private ResponseEntity<ApiResponse<String>> uploadBranding(MultipartFile file, boolean isLogo) {
        try {
            Utilisateurs currentUser = ensureAdmin();
            Farm farm = getCurrentUserFarm();
            if (farm == null) {
                return ApiResponse.createResponse("Aucune ferme associée à ce compte", HttpStatus.BAD_REQUEST, null, null);
            }
            if (file == null || file.isEmpty()) {
                return ApiResponse.createResponse("Fichier manquant", HttpStatus.BAD_REQUEST, null, List.of("Aucun fichier reçu"));
            }
            String ancien = isLogo ? farm.getLogoNomMinio() : farm.getTamponNomMinio();
            String nomMinio = minioService.uploadFile(file, isLogo ? "farm-logo" : "farm-tampon");
            if (isLogo) farm.setLogoNomMinio(nomMinio); else farm.setTamponNomMinio(nomMinio);
            farmsRepo.save(farm);
            if (ancien != null) {
                try { minioService.deleteFile(ancien); } catch (Exception ignored) { }
            }
            return ApiResponse.createResponse((isLogo ? "Logo" : "Tampon") + " mis à jour", HttpStatus.OK,
                    minioService.getPresignedUrl(nomMinio), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur lors de l'upload", HttpStatus.INTERNAL_SERVER_ERROR, null, List.of(e.getMessage()));
        }
    }

    private ResponseEntity<ApiResponse<String>> removeBranding(boolean isLogo) {
        try {
            ensureAdmin();
            Farm farm = getCurrentUserFarm();
            if (farm == null) {
                return ApiResponse.createResponse("Aucune ferme associée à ce compte", HttpStatus.BAD_REQUEST, null, null);
            }
            String ancien = isLogo ? farm.getLogoNomMinio() : farm.getTamponNomMinio();
            if (ancien != null) {
                try { minioService.deleteFile(ancien); } catch (Exception ignored) { }
                if (isLogo) farm.setLogoNomMinio(null); else farm.setTamponNomMinio(null);
                farmsRepo.save(farm);
            }
            return ApiResponse.createResponse((isLogo ? "Logo" : "Tampon") + " retiré", HttpStatus.OK, null, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    private String presignedUrlOrNull(String nomMinio) {
        if (nomMinio == null) return null;
        try {
            return minioService.getPresignedUrl(nomMinio);
        } catch (Exception e) {
            return null;
        }
    }

    // Logo/tampon apparaissent sur des documents officiels (factures, bulletins de
    // salaire) : réservé à ADMIN/SUPER_ADMIN, comme le reste de la page Paramètres
    // côté web (voir DashboardLayout.navItems).
    private Utilisateurs ensureAdmin() {
        Utilisateurs currentUser = otherService.getCurrentUser();
        boolean isAdmin = currentUser != null && currentUser.getRoles() != null && currentUser.getRoles().stream()
                .anyMatch(r -> "ADMIN".equalsIgnoreCase(r.getRole()) || "SUPER_ADMIN".equalsIgnoreCase(r.getRole()));
        if (!isAdmin) {
            throw new IllegalArgumentException("Seul un administrateur peut modifier l'identité visuelle de la ferme.");
        }
        return currentUser;
    }

    // currentUser.getFarm() est une association LAZY : appeler un accesseur autre que
    // getId() dessus hors transaction lève LazyInitializationException (open-in-view
    // désactivé). On ne touche donc que l'id (déjà connu du proxy sans requête) puis on
    // recharge la ferme via son propre repo, pleinement initialisée.
    private Farm getCurrentUserFarm() {
        Utilisateurs currentUser = otherService.getCurrentUser();
        if (currentUser == null || currentUser.getFarm() == null) return null;
        return farmsRepo.findById(currentUser.getFarm().getId()).orElse(null);
    }
}
