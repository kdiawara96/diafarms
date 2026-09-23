package com.diafarms.ml.controllers;

import java.time.LocalDate;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.diafarms.ml.DTO.VenteLigneDTO;
import com.diafarms.ml.ServiceImpl.VenteListeImpl;
import com.diafarms.ml.others.ApiResponse;

import lombok.RequiredArgsConstructor;

// Toutes les ventes (œufs, réforme, fientes, autres) lues dans leurs propres tables —
// voir VenteListeImpl.
@RestController
@RequestMapping("/diafarms/api/v1/ventes")
@RequiredArgsConstructor
public class VentesControllers {

    private final VenteListeImpl venteListe;

    @GetMapping("/list")
    public ResponseEntity<ApiResponse<List<VenteLigneDTO>>> list(
            @RequestParam(required = false) String dateDebut,
            @RequestParam(required = false) String dateFin,
            @RequestParam(required = false) String vendeurUniqueId) {
        try {
            LocalDate deb = (dateDebut != null && !dateDebut.isBlank()) ? LocalDate.parse(dateDebut) : null;
            LocalDate fin = (dateFin != null && !dateFin.isBlank()) ? LocalDate.parse(dateFin) : null;
            return ApiResponse.createResponse("Liste des ventes récupérée", HttpStatus.OK,
                    venteListe.list(deb, fin, vendeurUniqueId), null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            e.printStackTrace();
            return ApiResponse.createResponse("Erreur lors de la récupération des ventes", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }
}
