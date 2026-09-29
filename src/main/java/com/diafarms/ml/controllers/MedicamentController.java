package com.diafarms.ml.controllers;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.AchatMedicamentDTO;
import com.diafarms.ml.DTO.StockMedicamentDTO;
import com.diafarms.ml.ServiceImpl.MedicamentService;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.AchatMedicamentCreate;

import lombok.RequiredArgsConstructor;

// Achats de médicaments / vaccins et stock par projet (voir MedicamentService).
@RestController
@RequestMapping("/diafarms/api/v1/medicaments")
@RequiredArgsConstructor
public class MedicamentController {

    private final MedicamentService service;

    private <T> ResponseEntity<ApiResponse<T>> repondre(String message, HttpStatus ok, Supplier<T> action) {
        try {
            return ApiResponse.createResponse(message, ok, action.get(), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/create/{projetUniqueId}")
    public ResponseEntity<ApiResponse<AchatMedicamentDTO>> creer(@PathVariable String projetUniqueId, @RequestBody AchatMedicamentCreate data) {
        return repondre("Achat de médicament enregistré", HttpStatus.CREATED, () -> service.creer(projetUniqueId, data));
    }

    @PutMapping("/update/{uniqueId}")
    public ResponseEntity<ApiResponse<AchatMedicamentDTO>> modifier(@PathVariable String uniqueId, @RequestBody AchatMedicamentCreate data) {
        return repondre("Achat de médicament modifié", HttpStatus.OK, () -> service.modifier(uniqueId, data));
    }

    @DeleteMapping("/delete/{uniqueId}")
    public ResponseEntity<ApiResponse<String>> supprimer(@PathVariable String uniqueId) {
        return repondre("Achat de médicament supprimé", HttpStatus.OK, () -> { service.supprimer(uniqueId); return "Achat supprimé"; });
    }

    @GetMapping("/{uniqueId}")
    public ResponseEntity<ApiResponse<AchatMedicamentDTO>> detail(@PathVariable String uniqueId) {
        return repondre("Achat de médicament", HttpStatus.OK, () -> service.detail(uniqueId));
    }

    @GetMapping("/list-by-projet/{projetUniqueId}")
    public ResponseEntity<ApiResponse<List<AchatMedicamentDTO>>> parProjet(@PathVariable String projetUniqueId) {
        return repondre("Achats de médicaments du projet", HttpStatus.OK, () -> service.parProjet(projetUniqueId));
    }

    @GetMapping("/stock/{projetUniqueId}")
    public ResponseEntity<ApiResponse<List<StockMedicamentDTO>>> stock(@PathVariable String projetUniqueId) {
        return repondre("Stock de médicaments du projet", HttpStatus.OK, () -> service.stock(projetUniqueId));
    }

    @GetMapping("/recents")
    public ResponseEntity<ApiResponse<List<AchatMedicamentDTO>>> recents(@RequestParam(defaultValue = "7") int jours) {
        return repondre("Achats de médicaments récents", HttpStatus.OK, () -> service.recents(jours));
    }
}
