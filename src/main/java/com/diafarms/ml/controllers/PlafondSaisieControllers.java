package com.diafarms.ml.controllers;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.diafarms.ml.DTO.PlafondSaisieDTO;
import com.diafarms.ml.commons.EffectifVivantHelper;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.repository.BatimentRepo;
import com.diafarms.ml.repository.ProjetsRepo;

import lombok.RequiredArgsConstructor;

// Plafond d'une saisie de production (collecte d'œufs, mortalité, réforme) pour un
// projet/bâtiment/date donnés : lecture seule, sert uniquement aux alertes en direct
// des formulaires. Le contrôle réel reste fait à l'enregistrement (CollecteOeufsImpl,
// MortaliteImpl, ReformeImpl).
@RestController
@RequestMapping("/diafarms/api/v1/plafond-saisie")
@RequiredArgsConstructor
public class PlafondSaisieControllers {

    private final EffectifVivantHelper effectif;
    private final ProjetsRepo projetsRepo;
    private final BatimentRepo batimentRepo;

    @GetMapping
    public ResponseEntity<ApiResponse<PlafondSaisieDTO>> get(
            @RequestParam String projetUniqueId,
            @RequestParam(required = false) String batimentUniqueId,
            @RequestParam(required = false) String date) {
        try {
            Projets projet = projetsRepo.findByUniqueId(projetUniqueId)
                    .orElseThrow(() -> new IllegalArgumentException("Projet introuvable : " + projetUniqueId));
            Batiment batiment = (batimentUniqueId != null && !batimentUniqueId.isBlank())
                    ? batimentRepo.findByUniqueId(batimentUniqueId) : null;
            LocalDate jour = (date != null && !date.isBlank()) ? LocalDate.parse(date) : LocalDate.now();

            int plafond = effectif.plafond(projet, batiment);
            int deja = effectif.oeufsDejaCollectes(projet, batiment, jour, null);
            PlafondSaisieDTO dto = PlafondSaisieDTO.builder()
                    .effectifVivant(plafond)
                    .perimetre(effectif.plafondParBatiment(batiment) ? "BATIMENT" : "PROJET")
                    .oeufsDejaCollectes(deja)
                    .oeufsRestants(Math.max(0, plafond - deja))
                    .build();
            return ApiResponse.createResponse("Plafond de saisie récupéré", HttpStatus.OK, dto, null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse(e.getMessage(), HttpStatus.NOT_FOUND, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
