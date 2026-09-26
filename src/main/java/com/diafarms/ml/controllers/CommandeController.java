package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.CommandeDTO;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.CommandeCreate;
import com.diafarms.ml.request.create.PaiementClientCreate;
import com.diafarms.ml.request.others.AnnulationCommandeRequest;
import com.diafarms.ml.request.others.MotifSuppressionRequest;
import com.diafarms.ml.services.CommandeService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/commandes")
@RequiredArgsConstructor
public class CommandeController {

    private final CommandeService service;

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<CommandeDTO>> create(@RequestBody CommandeCreate request) {
        try {
            return ApiResponse.createResponse("Commande créée avec succès", HttpStatus.CREATED, service.create(request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<CommandeDTO>> update(@PathVariable String uniqueId, @RequestBody CommandeCreate request) {
        try {
            return ApiResponse.createResponse("Commande mise à jour", HttpStatus.OK, service.update(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/{uniqueId}/confirmer")
    public ResponseEntity<ApiResponse<CommandeDTO>> confirmer(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Commande confirmée", HttpStatus.OK, service.confirmer(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Une commande n'ayant encore rien livré : plus de motif optionnel — voir
    // AnnulationCommandeRequest/CommandeServiceImpl.annuler. rembourserAcompte rend en
    // argent ce qui restait disponible côté de cette commande (sinon avance client).
    @PutMapping("/{uniqueId}/annuler")
    public ResponseEntity<ApiResponse<CommandeDTO>> annuler(@PathVariable String uniqueId,
            @RequestBody(required = false) AnnulationCommandeRequest request) {
        try {
            boolean rembourser = request != null && Boolean.TRUE.equals(request.getRembourserAcompte());
            String motif = request != null ? request.getMotif() : null;
            String mode = request != null ? request.getMode() : null;
            return ApiResponse.createResponse("Commande annulée", HttpStatus.OK,
                    service.annuler(uniqueId, motif, rembourser, mode), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Clôture une commande livrée en partie seulement — voir CommandeServiceImpl.cloturer.
    @PutMapping("/{uniqueId}/cloturer")
    public ResponseEntity<ApiResponse<CommandeDTO>> cloturer(@PathVariable String uniqueId,
            @RequestBody(required = false) MotifSuppressionRequest request) {
        try {
            return ApiResponse.createResponse("Commande clôturée", HttpStatus.OK,
                    service.cloturer(uniqueId, request != null ? request.getMotif() : null), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/{uniqueId}/convertir-en-vente")
    public ResponseEntity<ApiResponse<CommandeDTO>> convertirEnVente(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse("Commande convertie en vente", HttpStatus.OK, service.convertirEnVente(uniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Livraison partielle ou totale d'une commande (grosse commande livrée au fil de la
    // collecte, en plusieurs fois) — voir CommandeServiceImpl.livrer. quantite absente =
    // tout ce qu'il reste (même résultat que /convertir-en-vente) ; montantRecu = argent
    // NOUVEAU reçu à CETTE livraison précise, 0/absent si rien de neuf ; mode = mode de
    // paiement de cet argent nouveau, ESPECES par défaut. Commande de réforme au KILO :
    // quantite (sujets) et poidsTotalKg (poids pesé) obligatoires, prixKg optionnel
    // (défaut = prixKgEstime de la commande) ; montant = poidsTotalKg x prixKg.
    // date (AAAA-MM-JJ) / heure (HH:mm) optionnelles : date réelle d'une livraison saisie
    // hors ligne ; absentes = aujourd'hui.
    @PostMapping("/{uniqueId}/livrer")
    public ResponseEntity<ApiResponse<CommandeDTO>> livrer(
            @PathVariable String uniqueId,
            @RequestParam(required = false) Integer quantite,
            @RequestParam(required = false) Double montantRecu,
            @RequestParam(required = false) String mode,
            @RequestParam(required = false) Double poidsTotalKg,
            @RequestParam(required = false) Double prixKg,
            @RequestParam(required = false) String date,
            @RequestParam(required = false) String heure) {
        try {
            return ApiResponse.createResponse("Commande livrée", HttpStatus.OK,
                    service.livrer(uniqueId, quantite, montantRecu, mode, poidsTotalKg, prixKg, date, heure), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Paiement (acompte ou règlement) rattaché à cette commande — le client et la
    // commande sont déduits de l'URL, pas du corps (voir CommandeServiceImpl.enregistrerPaiement).
    @PostMapping("/{uniqueId}/paiement")
    public ResponseEntity<ApiResponse<CommandeDTO>> paiement(@PathVariable String uniqueId,
            @RequestBody PaiementClientCreate request) {
        try {
            return ApiResponse.createResponse("Paiement enregistré", HttpStatus.CREATED,
                    service.enregistrerPaiement(uniqueId, request), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PutMapping("/deleteOrRecover/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecover(@PathVariable String uniqueId) {
        try {
            return ApiResponse.createResponse(service.deleteOrRecover(uniqueId), HttpStatus.OK, null, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<CommandeDTO>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String statut,
            @RequestParam(required = false) String clientUniqueId) {
        try {
            return ApiResponse.createResponse("Liste des commandes récupérée", HttpStatus.OK, service.list(page, size, statut, clientUniqueId), null);
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
