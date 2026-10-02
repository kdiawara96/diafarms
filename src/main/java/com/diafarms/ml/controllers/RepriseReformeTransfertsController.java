package com.diafarms.ml.controllers;

import java.util.List;
import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.RepriseReformeTransfertsDTO;
import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.ServiceImpl.ReformeTransfertsManquantsService;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.repository.FarmsRepo;

import lombok.RequiredArgsConstructor;

// Reprise des transferts de réformés vers un point de vente (voir
// ReformeTransfertsManquantsService). executer=false (défaut) : simulation, rien n'est
// écrit. SUPER_ADMIN : n'importe quelle ferme (farmUniqueId obligatoire) ; ADMIN : sa
// propre ferme uniquement (farmUniqueId facultatif, refusé s'il désigne une autre ferme).
// magasinVenteUniqueId facultatif : sinon point de vente par défaut (ReformePointDeVente).
@RestController
@RequestMapping("/diafarms/api/v1")
@RequiredArgsConstructor
public class RepriseReformeTransfertsController {
    private final ReformeTransfertsManquantsService service;
    private final OtherService otherService;
    private final FarmsRepo farmsRepo;

    private static boolean hasRole(Utilisateurs u, String r) {
        return u != null && u.getRoles() != null && u.getRoles().stream().anyMatch(x -> r.equalsIgnoreCase(x.getRole()));
    }

    @PostMapping("/admin/reformes/transferts-manquants")
    public ResponseEntity<ApiResponse<RepriseReformeTransfertsDTO>> reprise(
            @RequestParam(defaultValue = "false") boolean executer,
            @RequestParam(required = false) String farmUniqueId,
            @RequestParam(required = false) String magasinVenteUniqueId) {
        try {
            Utilisateurs u = otherService.getCurrentUser();
            if (u == null) return ApiResponse.createResponse("Non authentifié", HttpStatus.UNAUTHORIZED, null, null);
            boolean blank = farmUniqueId == null || farmUniqueId.isBlank();
            Farm farm;
            if (hasRole(u, "SUPER_ADMIN")) {
                if (blank) throw new IllegalArgumentException("Paramètre farmUniqueId obligatoire.");
                farm = farmsRepo.findByUniqueId(farmUniqueId);
                if (farm == null) throw new IllegalArgumentException("Ferme introuvable : " + farmUniqueId);
            } else if (hasRole(u, "ADMIN") && u.getFarm() != null) {
                farm = farmsRepo.findById(u.getFarm().getId()).orElseThrow(() -> new IllegalArgumentException("Ferme introuvable."));
                if (!blank && !Objects.equals(farm.getUniqueId(), farmUniqueId)) {
                    return ApiResponse.createResponse("Accès refusé", HttpStatus.FORBIDDEN, null,
                            List.of("Un administrateur ne peut traiter que sa propre ferme."));
                }
            } else {
                return ApiResponse.createResponse("Accès refusé", HttpStatus.FORBIDDEN, null,
                        List.of("Réservé à l'administrateur de la ferme ou au super-administrateur."));
            }
            RepriseReformeTransfertsDTO rapport = service.lancer(farm, executer, magasinVenteUniqueId, u);
            String msg = rapport.getErreur() != null ? rapport.getErreur()
                    : executer ? "Transferts des réformés créés" : "Simulation (rien n'a été écrit)";
            return ApiResponse.createResponse(msg, HttpStatus.OK, rapport, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null,
                    List.of(String.valueOf(e.getMessage())));
        }
    }
}
