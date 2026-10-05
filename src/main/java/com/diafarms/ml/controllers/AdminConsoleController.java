package com.diafarms.ml.controllers;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.AdminConsoleDTO;
import com.diafarms.ml.DTO.PaiementAbonnementDTO;
import com.diafarms.ml.ServiceImpl.AdminConsoleService;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.others.AdminActiverAbonnementRequest;
import com.diafarms.ml.request.others.AdminNoteRequest;
import com.diafarms.ml.request.others.AdminProlongerEssaiRequest;
import com.diafarms.ml.request.others.AdminSuspendreRequest;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

// Console d'administration SUPER_ADMIN (voir AdminConsoleService). Chaque appel vérifie
// le rôle SUPER_ADMIN côté serveur : tout autre compte reçoit 403.
@RestController
@RequestMapping("/diafarms/api/v1/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminConsoleController {

    private final AdminConsoleService service;

    private <T> ResponseEntity<ApiResponse<T>> repondre(String message, Supplier<T> action) {
        try {
            return ApiResponse.createResponse(message, HttpStatus.OK, action.get(), null);
        } catch (AccessDeniedException e) {
            return ApiResponse.createResponse("Accès refusé", HttpStatus.FORBIDDEN, null, List.of(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            log.error("Console d'administration : {}", e.getMessage(), e);
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/tableau-de-bord")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.TableauDeBord>> tableauDeBord() {
        return repondre("Vue d'ensemble", service::tableauDeBord);
    }

    @GetMapping("/fermes")
    public ResponseEntity<ApiResponse<List<AdminConsoleDTO.Ferme>>> fermes() {
        return repondre("Fermes", service::listerFermes);
    }

    @GetMapping("/fermes/{farmUniqueId}")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> ferme(@PathVariable String farmUniqueId) {
        return repondre("Fiche de la ferme", () -> service.detailFerme(farmUniqueId));
    }

    @PostMapping("/fermes/{farmUniqueId}/activer")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> activer(@PathVariable String farmUniqueId,
            @RequestBody(required = false) AdminActiverAbonnementRequest request) {
        return repondre("Abonnement activé", () -> service.activer(farmUniqueId, request));
    }

    @PostMapping("/fermes/{farmUniqueId}/suspendre")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> suspendre(@PathVariable String farmUniqueId,
            @RequestBody(required = false) AdminSuspendreRequest request) {
        return repondre("Ferme suspendue", () -> service.suspendre(farmUniqueId, request));
    }

    @PostMapping("/fermes/{farmUniqueId}/reactiver")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> reactiver(@PathVariable String farmUniqueId) {
        return repondre("Ferme réactivée", () -> service.reactiver(farmUniqueId));
    }

    @PostMapping("/fermes/{farmUniqueId}/prolonger-essai")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> prolongerEssai(@PathVariable String farmUniqueId,
            @RequestBody(required = false) AdminProlongerEssaiRequest request) {
        return repondre("Essai prolongé", () -> service.prolongerEssai(farmUniqueId, request));
    }

    @PostMapping("/fermes/{farmUniqueId}/notes")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> note(@PathVariable String farmUniqueId,
            @RequestBody(required = false) AdminNoteRequest request) {
        return repondre("Note ajoutée", () -> service.ajouterNote(farmUniqueId, request));
    }

    // Exclure (true) ou réintégrer (false) une ferme dans les statistiques : {"exclure": true}
    @PostMapping("/fermes/{farmUniqueId}/statistiques")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> statistiques(@PathVariable String farmUniqueId,
            @RequestBody(required = false) com.diafarms.ml.request.others.AdminStatistiquesRequest request) {
        return repondre("Statistiques mises à jour", () -> service.statistiques(farmUniqueId, request));
    }

    // Tarif spécial (prix fixe par mois) : {"prixMensuelFixe": 7500, "motif": "..."} ;
    // {"prixMensuelFixe": null} pour revenir au prix par poule.
    @PostMapping("/fermes/{farmUniqueId}/prix-fixe")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> prixFixe(@PathVariable String farmUniqueId,
            @RequestBody(required = false) com.diafarms.ml.request.others.AdminPrixFixeRequest request) {
        return repondre("Tarif mis à jour", () -> service.prixFixe(farmUniqueId, request));
    }

    @GetMapping("/finances")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.Finances>> finances(@RequestParam(required = false) Integer annee) {
        return repondre("Finances", () -> service.finances(annee));
    }

    @GetMapping("/paiements")
    public ResponseEntity<ApiResponse<List<PaiementAbonnementDTO>>> paiements(
            @RequestParam(required = false) String du,
            @RequestParam(required = false) String au,
            @RequestParam(required = false) String ferme,
            @RequestParam(required = false) String statut) {
        return repondre("Paiements", () -> service.paiements(du, au, ferme, statut));
    }

    @GetMapping("/journal")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.JournalPage>> journal(
            @RequestParam(required = false) String du,
            @RequestParam(required = false) String au,
            @RequestParam(required = false) String categorie,
            @RequestParam(required = false) String ferme,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return repondre("Journal", () -> service.journalPage(du, au, categorie, ferme, page, size));
    }
}
