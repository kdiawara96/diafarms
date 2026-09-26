package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.RepriseAcompteReserveRapportDTO;
import com.diafarms.ml.DTO.RepriseRapportDTO;
import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.ServiceImpl.RepriseAcompteReserveService;
import com.diafarms.ml.ServiceImpl.RepriseCircuitClientService;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.repository.FarmsRepo;

import lombok.RequiredArgsConstructor;

// Reprise des données clients vers le circuit paiement/imputation. executer=false
// (défaut) : simulation, rien n'est écrit. Réservée au SUPER_ADMIN (simulation comme
// exécution : c'est une opération de déploiement, pas de gestion de ferme) : la ferme
// farmUniqueId, ou toutes les fermes si absent. Tout autre rôle, ADMIN compris -> 403.
@RestController
@RequestMapping("/diafarms/api/v1")
@RequiredArgsConstructor
public class RepriseCircuitClientController {
    private final RepriseCircuitClientService service;
    private final RepriseAcompteReserveService acompteReserveService;
    private final OtherService otherService;
    private final FarmsRepo farmsRepo;

    private static boolean hasRole(Utilisateurs u, String r) {
        return u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(x -> r.equalsIgnoreCase(x.getRole()));
    }

    @PostMapping("/admin/reprise-circuit-client")
    public ResponseEntity<ApiResponse<RepriseRapportDTO>> reprise(
            @RequestParam(defaultValue = "false") boolean executer,
            @RequestParam(required = false) String farmUniqueId) {
        try {
            Utilisateurs u = otherService.getCurrentUser();
            if (u == null) return ApiResponse.createResponse("Non authentifié", HttpStatus.UNAUTHORIZED, null, null);
            List<Farm> farms;
            if (hasRole(u, "SUPER_ADMIN")) {
                if (farmUniqueId != null && !farmUniqueId.isBlank()) {
                    Farm f = farmsRepo.findByUniqueId(farmUniqueId);
                    if (f == null) throw new IllegalArgumentException("Ferme introuvable : " + farmUniqueId);
                    farms = List.of(f);
                } else {
                    farms = farmsRepo.findAll();
                }
            } else {
                return ApiResponse.createResponse("Accès refusé", HttpStatus.FORBIDDEN, null,
                        List.of("Réservé au super-administrateur."));
            }
            RepriseRapportDTO rapport = service.lancer(farms, executer, u);
            return ApiResponse.createResponse(executer ? "Reprise exécutée" : "Simulation de la reprise (rien n'a été écrit)",
                    HttpStatus.OK, rapport, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null,
                    List.of(String.valueOf(e.getMessage())));
        }
    }

    // Reprise « acompte réservé » (voir RepriseAcompteReserveService) : mêmes règles
    // d'accès et de périmètre que la reprise ci-dessus. executer=false (défaut) :
    // simulation, rapport avant/après par client, rien n'est écrit.
    @PostMapping("/admin/reprise-acompte-reserve")
    public ResponseEntity<ApiResponse<RepriseAcompteReserveRapportDTO>> repriseAcompteReserve(
            @RequestParam(defaultValue = "false") boolean executer,
            @RequestParam(required = false) String farmUniqueId) {
        try {
            Utilisateurs u = otherService.getCurrentUser();
            if (u == null) return ApiResponse.createResponse("Non authentifié", HttpStatus.UNAUTHORIZED, null, null);
            if (!hasRole(u, "SUPER_ADMIN")) {
                return ApiResponse.createResponse("Accès refusé", HttpStatus.FORBIDDEN, null,
                        List.of("Réservé au super-administrateur."));
            }
            List<Farm> farms;
            if (farmUniqueId != null && !farmUniqueId.isBlank()) {
                Farm f = farmsRepo.findByUniqueId(farmUniqueId);
                if (f == null) throw new IllegalArgumentException("Ferme introuvable : " + farmUniqueId);
                farms = List.of(f);
            } else {
                farms = farmsRepo.findAll();
            }
            RepriseAcompteReserveRapportDTO rapport = acompteReserveService.lancer(farms, executer, u);
            return ApiResponse.createResponse(executer ? "Reprise acompte réservé exécutée"
                    : "Simulation de la reprise acompte réservé (rien n'a été écrit)", HttpStatus.OK, rapport, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null,
                    List.of(String.valueOf(e.getMessage())));
        }
    }
}
