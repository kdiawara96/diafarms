package com.diafarms.ml.controllers;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.ProjetAssigneDTO;
import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.DTO.ProjetsSelect;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ProjetCreate;
import com.diafarms.ml.request.update.ProjetUpdate;
import com.diafarms.ml.services.ProjetServices;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/diafarms/api/v1/projets")
@RequiredArgsConstructor
public class ProjetsControllers {

    private final ProjetServices services;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<PaginatedResponse<ProjetsDTO>>> getProjets(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "tous") String filter) {
        
        // Récupération de la réponse paginée depuis le service
        PaginatedResponse<ProjetsDTO> response = services.getAllProjets(page, size, search, filter);

        // Encapsulation uniforme dans l'ApiResponse globale
        return ApiResponse.createResponse(
                "Liste des projets récupérée avec succès",
                HttpStatus.OK,
                response,
                null
        );
    }

    @PostMapping("/create")
    public ResponseEntity<ApiResponse<ProjetsDTO>> create(@RequestBody ProjetCreate request) {
        try {
            ProjetsDTO result = services.createProjet(request);
            return ApiResponse.createResponse("Projet créé avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
    
    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<ProjetsDTO>> update(
            @PathVariable String uniqueId,
            @RequestBody ProjetUpdate request) {
        try {
            ProjetsDTO result = services.updateProjet(uniqueId, request);
            return ApiResponse.createResponse("Projet mis à jour avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @GetMapping("/findbyUniqueId/{uniqueId}")
    public ResponseEntity<ApiResponse<ProjetsDTO>> findByUniqueId(@PathVariable String uniqueId) {
        try {
            ProjetsDTO result = services.getProjetByUniqueId(uniqueId);
            return ApiResponse.createResponse("Projet mis à jour avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @DeleteMapping("/delete/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> deleteOrRecoverProjet(@PathVariable String uniqueId) {
        try {
            String result = services.deleteOrRecoverProjet(uniqueId);
            return ApiResponse.createResponse("Projet mis à jour avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Clôture d'un projet — distinct de /delete (corbeille) : voir
    // ProjetImpl.cloturerProjet. C'est cette action, explicite côté admin, qui fait
    // disparaître un projet du mobile hors ligne (ProjetsSelect.active), pas sa date
    // de fin prévue. Rejette explicitement un projet déjà clôturé (voir rouvrirProjet
    // pour l'action inverse) plutôt que de re-toggler en silence.
    @PutMapping("/cloturer/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> cloturerProjet(@PathVariable String uniqueId) {
        try {
            String result = services.cloturerProjet(uniqueId);
            return ApiResponse.createResponse("Projet clôturé avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    // Réouverture d'un projet clôturé — refusée si la date de fin prévue est déjà
    // passée (voir ProjetImpl.rouvrirProjet). Le stock d'aliment déjà transféré vers
    // un autre projet lors de la clôture (transferer-stock) n'est jamais restitué.
    @PutMapping("/rouvrir/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> rouvrirProjet(@PathVariable String uniqueId) {
        try {
            String result = services.rouvrirProjet(uniqueId);
            return ApiResponse.createResponse("Projet rouvert avec succès", HttpStatus.OK, result, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (RuntimeException e) {
            return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

        // Transfert du stock d'aliment restant vers un autre projet, typiquement juste
        // avant la clôture — voir ProjetImpl.transfererStock.
        @PutMapping("/{uniqueId}/transferer-stock/{projetCibleUniqueId}")
        public ResponseEntity<ApiResponse<String>> transfererStock(
                @PathVariable String uniqueId,
                @PathVariable String projetCibleUniqueId) {
            try {
                String result = services.transfererStock(uniqueId, projetCibleUniqueId);
                return ApiResponse.createResponse("Transfert effectué", HttpStatus.OK, result, null);
            } catch (IllegalArgumentException e) {
                return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
            } catch (RuntimeException e) {
                return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
            } catch (Exception e) {
                return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
            }
        }

        @GetMapping("/select")
        public ResponseEntity<ApiResponse<List<ProjetsSelect>>> selectProjet() {
            try {
                List<ProjetsSelect> result = services.selectEntity();
                return ApiResponse.createResponse("Projet mis à jour avec succès", HttpStatus.OK, result, null);
            } catch (IllegalArgumentException e) {
                return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
            } catch (RuntimeException e) {
                return ApiResponse.createResponse("Erreur", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
            } catch (Exception e) {
                return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
            }
        }

        // Derniers projets associés à un utilisateur (production et/ou finance), pour la
        // modale "Profil & Accès Mobile Utilisateur" côté web.
        @GetMapping("/assignes/{userUniqueId}")
        public ResponseEntity<ApiResponse<List<ProjetAssigneDTO>>> getProjetsAssignes(
                @PathVariable String userUniqueId,
                @RequestParam(defaultValue = "7") int limit) {
            try {
                List<ProjetAssigneDTO> result = services.getProjetsAssignes(userUniqueId, limit);
                return ApiResponse.createResponse("Projets associés récupérés avec succès", HttpStatus.OK, result, null);
            } catch (Exception e) {
                return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
            }
        }
}
