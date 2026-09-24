package com.diafarms.ml.controllers;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.PaiementClientDTO;
import com.diafarms.ml.DTO.RemboursementClientDTO;
import com.diafarms.ml.ServiceImpl.PaiementClientService;
import com.diafarms.ml.others.ApiResponse;
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
    // Assemblage (recherche client + vérif ferme + calcul du compte + mapping des
    // historiques en DTO) délégué à PaiementClientService.compteComplet, exécuté dans une
    // seule transaction en lecture seule : les associations paresseuses des entités
    // (p.client, r.effectuePar, i.paiement) doivent être accédées pendant que la session
    // Hibernate est encore ouverte (open-in-view=false), pas après coup dans le contrôleur.
    @GetMapping("/clients/{uid}/compte")
    public ResponseEntity<ApiResponse<Map<String, Object>>> compte(@PathVariable String uid) {
        try {
            return ApiResponse.createResponse("Compte récupéré avec succès", HttpStatus.OK, service.compteComplet(uid), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
