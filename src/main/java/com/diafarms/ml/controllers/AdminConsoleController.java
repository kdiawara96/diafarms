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
    private final com.diafarms.ml.ServiceImpl.EssaiEmailsService essaiEmails;
    private final com.diafarms.ml.ServiceImpl.ResumeHebdoService resumeHebdo;
    private final com.diafarms.ml.ServiceImpl.ParrainageService parrainage;
    private final com.diafarms.ml.ServiceImpl.CreditService credit;

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

    // Ajustement du crédit (crédit prépayé) : {"montant": 5000, "motif": "..."} ; montant
    // négatif pour retirer du crédit.
    @PostMapping("/fermes/{farmUniqueId}/ajustement")
    public ResponseEntity<ApiResponse<AdminConsoleDTO.FermeDetail>> ajustement(@PathVariable String farmUniqueId,
            @RequestBody(required = false) com.diafarms.ml.request.others.AdminAjustementRequest request) {
        return repondre("Crédit ajusté", () -> service.ajuster(farmUniqueId, request));
    }

    // Tâche du crédit (chaque jour à 00 h 30 UTC : mensualités des mois terminés, échéances,
    // e-mails). executer=false (par défaut) : simulation, rien n'est écrit ni envoyé.
    @PostMapping("/credit/tache")
    public ResponseEntity<ApiResponse<com.diafarms.ml.ServiceImpl.CreditService.ResultatTache>> tacheCredit(
            @RequestParam(defaultValue = "false") boolean executer) {
        return repondre(executer ? "Tâche du crédit exécutée" : "Simulation de la tâche du crédit", () -> {
            service.verifierSuperAdmin();
            return credit.executer(executer);
        });
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

    // E-mails de démarrage pendant l'essai (J+1, J+3, J+7) : simulation par défaut
    // (envoyer=false), rien n'est envoyé ni enregistré.
    @PostMapping("/emails-essai")
    public ResponseEntity<ApiResponse<List<com.diafarms.ml.ServiceImpl.EssaiEmailsService.EnvoiDTO>>> emailsEssai(
            @RequestParam(defaultValue = "false") boolean envoyer) {
        return repondre(envoyer ? "E-mails d'essai envoyés" : "Simulation des e-mails d'essai",
                () -> essaiEmails.executerManuellement(envoyer));
    }

    // Résumé de la semaine : simulation par défaut. semaine = date du lundi (défaut : la
    // semaine passée), ferme = farmUniqueId pour une seule ferme (facultatif).
    @PostMapping("/resume-semaine")
    public ResponseEntity<ApiResponse<List<com.diafarms.ml.ServiceImpl.ResumeHebdoService.ResumeDTO>>> resumeSemaine(
            @RequestParam(defaultValue = "false") boolean envoyer,
            @RequestParam(required = false) String semaine,
            @RequestParam(required = false) String ferme) {
        return repondre(envoyer ? "Résumés envoyés" : "Simulation des résumés", () -> {
            java.time.LocalDate lundi = null;
            if (semaine != null && !semaine.isBlank()) {
                try {
                    lundi = java.time.LocalDate.parse(semaine.trim());
                } catch (java.time.format.DateTimeParseException e) {
                    throw new IllegalArgumentException("Date invalide : " + semaine);
                }
            }
            return resumeHebdo.executerManuellement(envoyer, lundi, ferme == null || ferme.isBlank() ? null : ferme.trim());
        });
    }

    // Tous les parrainages (plus récents d'abord) et les récompenses données.
    @GetMapping("/parrainages")
    public ResponseEntity<ApiResponse<List<com.diafarms.ml.ServiceImpl.ParrainageService.LigneParrainage>>> parrainages() {
        return repondre("Parrainages", () -> {
            service.verifierSuperAdmin();
            return parrainage.tous();
        });
    }
}
