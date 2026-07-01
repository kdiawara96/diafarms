package com.diafarms.ml.controllers;

import com.diafarms.ml.DTO.InvestissementDTO;
import com.diafarms.ml.DTO.InvestissementRepartitionDTO;
import com.diafarms.ml.DTO.InvestissementStatsDTO;
import com.diafarms.ml.commons.SecurityUtils;
import com.diafarms.ml.models.Investissement;
import com.diafarms.ml.models.InvestissementRepartition;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.InvestissementRequest;
import com.diafarms.ml.services.InvestissementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/diafarms/api/v1/investissements")
@RequiredArgsConstructor
public class InvestissementControllers {

    private final InvestissementService investissementService;

    // 📁 Récupérer la liste complète des investissements d'une ferme
    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<InvestissementDTO>>> getInvestissements(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        
        String uniqueId = SecurityUtils.getCurrentUserUniqueId();
        
        PaginatedResponse<InvestissementDTO> response = investissementService.getInvestissementsPagines(uniqueId, page, size);

        return ApiResponse.createResponse(
                "Liste paginée des investissements récupérée avec succès",
                HttpStatus.OK,
                response,
                null
        );
    }

    // 🔍 Récupérer un investissement spécifique par son identifiant unique
    @GetMapping("/find/{uniqueId}")
    public ResponseEntity<ApiResponse<InvestissementDTO>> getByUniqueId(@PathVariable String uniqueId) {
        try {
            InvestissementDTO result = investissementService.getInvestissementParUniqueId(uniqueId);
            return ApiResponse.createResponse("Investissement trouvé", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // ➕ Créer un nouvel investissement
    // Note : Tu pourras adapter les paramètres farmId et utilisateurId selon ton contexte de session/sécurité
    @PostMapping("/create")
    public ResponseEntity<ApiResponse<InvestissementDTO>> create(
            @RequestBody InvestissementRequest request) {
        try {
               // Récupère le uniqueId directement du token JWT
                String uniqueId = SecurityUtils.getCurrentUserUniqueId();

            InvestissementDTO result = investissementService.creerInvestissement(request, uniqueId);
            return ApiResponse.createResponse("Investissement créé avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // ✏️ Modifier un investissement existant
    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<InvestissementDTO>> update(
            @PathVariable String uniqueId,
            @RequestBody Investissement request) {
        try {
            InvestissementDTO result = investissementService.modifierInvestissement(uniqueId, request);
            return ApiResponse.createResponse("Investissement mis à jour avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // 🗑️ Supprimer un investissement (Soft Delete)
    @DeleteMapping("/delete/{uniqueId}")
    public ResponseEntity<ApiResponse<Map<String, String>>> delete(@PathVariable String uniqueId) {
        try {
            investissementService.supprimerInvestissement(uniqueId);
            Map<String, String> result = Map.of("message", "L'investissement a été marqué comme supprimé.");
            return ApiResponse.createResponse("Investissement supprimé avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // =========================================================================
    // ENDPOINTS POUR LES RÉPARTITIONS / VENTILATIONS D'AMORTISSEMENTS
    // =========================================================================

    // 📊 Liste des ventilations pour un actif
    @GetMapping("/repartitions/{uniqueId}")
    public ResponseEntity<ApiResponse<List<InvestissementRepartitionDTO>>> getRepartitions(@PathVariable String uniqueId) {
        List<InvestissementRepartitionDTO> list = investissementService.getRepartitionsParInvestissement(uniqueId);
        return ApiResponse.createResponse("Historique de répartition récupéré", HttpStatus.OK, list, null);
    }

    // 🔗 Assigner une part d'amortissement à un projet spécifique
    @PostMapping("/repartitions/{invUniqueId}/lier/{projetUniqueId}")
    public ResponseEntity<ApiResponse<InvestissementRepartitionDTO>> lierAuProjet(
            @PathVariable String invUniqueId,
            @PathVariable String projetUniqueId,
            @RequestBody InvestissementRepartition repartition) {
        try {
            InvestissementRepartitionDTO result = investissementService.ajouterRepartition(invUniqueId, projetUniqueId, repartition);
            return ApiResponse.createResponse("Répartition enregistrée avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données ou IDs de liaisons invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // 📉 Connaître le coût d'amortissement total cumulé absorbé par un projet (ex: pour déduire de la marge nette)
    @GetMapping("/projets/{projetUniqueId}/cout-amortissement")
    public ResponseEntity<ApiResponse<Double>> getCoutAmortissementProjet(@PathVariable String projetUniqueId) {
        Double total = investissementService.getCoutAmortissementProjet(projetUniqueId);
        return ApiResponse.createResponse("Charge d'amortissement totale calculée pour le projet", HttpStatus.OK, total, null);
    }

    @GetMapping("/stats")
    public ResponseEntity<ApiResponse<InvestissementStatsDTO>> getStats() {
        String utilisateurUniqueId = SecurityUtils.getCurrentUserUniqueId();
        
        InvestissementStatsDTO stats = investissementService.getInvestissementsStats(utilisateurUniqueId);
        
        return ApiResponse.createResponse(
                "Statistiques globales récupérées avec succès",
                HttpStatus.OK,
                stats,
                null
        );
    }
}