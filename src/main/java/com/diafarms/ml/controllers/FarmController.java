package com.diafarms.ml.controllers;

import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.repository.FarmsRepo;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Endpoints minimaux pour la ferme courante — pour l'instant limités à la ville
 * (utilisée par WeatherService pour géolocaliser les alertes météo). Pas encore de
 * gestion complète de la ferme (nom, adresse...), à étendre si le besoin apparaît.
 */
@RestController
@RequestMapping("/diafarms/api/v1/farms")
@RequiredArgsConstructor
public class FarmController {

    private final FarmsRepo farmsRepo;
    private final OtherService otherService;

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<Map<String, String>>> getMyFarm() {
        Farm farm = getCurrentUserFarm();
        if (farm == null) {
            return ApiResponse.createResponse("Aucune ferme associée à ce compte", HttpStatus.OK, null, null);
        }
        return ApiResponse.createResponse("Ferme récupérée", HttpStatus.OK,
                Map.of("uniqueId", farm.getUniqueId(), "ville", farm.getVille() == null ? "" : farm.getVille()), null);
    }

    @PutMapping("/me/ville")
    public ResponseEntity<ApiResponse<String>> setMyFarmVille(@RequestBody Map<String, String> body) {
        Farm farm = getCurrentUserFarm();
        if (farm == null) {
            return ApiResponse.createResponse("Aucune ferme associée à ce compte", HttpStatus.BAD_REQUEST, null, null);
        }
        String ville = body.get("ville");
        farm.setVille(ville == null || ville.isBlank() ? null : ville.trim());
        farmsRepo.save(farm);
        return ApiResponse.createResponse("Ville de la ferme mise à jour", HttpStatus.OK, farm.getVille(), null);
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
