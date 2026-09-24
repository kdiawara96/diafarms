package com.diafarms.ml.controllers;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.ImputationDTO;
import com.diafarms.ml.DTO.PaiementClientDTO;
import com.diafarms.ml.DTO.RemboursementClientDTO;
import com.diafarms.ml.ServiceImpl.CompteClientService;
import com.diafarms.ml.ServiceImpl.OtherService;
import com.diafarms.ml.ServiceImpl.PaiementClientService;
import com.diafarms.ml.commons.CalculImputation;
import com.diafarms.ml.models.Client;
import com.diafarms.ml.models.PaiementClient;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.repository.ClientRepo;
import com.diafarms.ml.repository.ImputationPaiementRepo;
import com.diafarms.ml.repository.PaiementClientRepo;
import com.diafarms.ml.repository.RemboursementClientRepo;
import com.diafarms.ml.request.create.PaiementClientCreate;
import com.diafarms.ml.request.create.RemboursementClientCreate;
import com.diafarms.ml.request.others.MotifSuppressionRequest;

import lombok.RequiredArgsConstructor;

// Paiements/remboursements client (voir PaiementClientService) et compte complet d'un
// client (chiffres + historique) — même style de réponses que VenteDiverseControllers.
@RestController
@RequestMapping("/diafarms/api/v1")
@RequiredArgsConstructor
public class PaiementClientController {
    private final PaiementClientService service;
    private final CompteClientService compteService;
    private final ClientRepo clientRepo;
    private final PaiementClientRepo paiementRepo;
    private final RemboursementClientRepo remboursementRepo;
    private final ImputationPaiementRepo imputationRepo;
    private final OtherService otherService;

    @PostMapping("/paiements-client/create")
    public ResponseEntity<ApiResponse<PaiementClientDTO>> create(@RequestBody PaiementClientCreate r) {
        try {
            return ApiResponse.createResponse("Paiement enregistré avec succès", HttpStatus.CREATED, service.enregistrer(r), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/paiements-client/annuler/{uid}")
    public ResponseEntity<ApiResponse<PaiementClientDTO>> annuler(@PathVariable String uid,
            @RequestBody(required = false) MotifSuppressionRequest r) {
        try {
            return ApiResponse.createResponse("Paiement annulé", HttpStatus.OK,
                    service.annuler(uid, r != null ? r.getMotif() : null), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/remboursements-client/create")
    public ResponseEntity<ApiResponse<RemboursementClientDTO>> rembourser(@RequestBody RemboursementClientCreate r) {
        try {
            return ApiResponse.createResponse("Remboursement enregistré avec succès", HttpStatus.CREATED, service.rembourser(r), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/remboursements-client/annuler/{uid}")
    public ResponseEntity<ApiResponse<RemboursementClientDTO>> annulerRemb(@PathVariable String uid,
            @RequestBody(required = false) MotifSuppressionRequest r) {
        try {
            return ApiResponse.createResponse("Remboursement annulé", HttpStatus.OK,
                    service.annulerRemboursement(uid, r != null ? r.getMotif() : null), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Compte complet d'un client : chiffres + historique (annulés compris).
    @GetMapping("/clients/{uid}/compte")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compte(@PathVariable String uid) {
        try {
            Utilisateurs currentUser = otherService.getCurrentUser();
            Client c = clientRepo.findByUniqueId(uid);
            if (c == null || currentUser == null || currentUser.getFarm() == null
                    || !c.getFarm().getId().equals(currentUser.getFarm().getId())) {
                throw new IllegalArgumentException("Client introuvable");
            }

            List<PaiementClientDTO> paiements = paiementRepo.findAllByClientIdForHistorique(c.getId()).stream()
                    .map((PaiementClient p) -> PaiementClientDTO.fromEntity(p,
                            CalculImputation.arrondi(imputationRepo.sumActivesByPaiementId(p.getId()))))
                    .toList();
            List<RemboursementClientDTO> remboursements = remboursementRepo.findAllByClientId(c.getId()).stream()
                    .map(RemboursementClientDTO::fromEntity)
                    .toList();
            List<ImputationDTO> imputations = imputationRepo.findAllByClientId(c.getId()).stream()
                    .map(ImputationDTO::fromEntity)
                    .toList();

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("compte", compteService.compte(c));
            data.put("paiements", paiements);
            data.put("remboursements", remboursements);
            data.put("imputations", imputations);

            return ApiResponse.createResponse("Compte récupéré avec succès", HttpStatus.OK, data, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
