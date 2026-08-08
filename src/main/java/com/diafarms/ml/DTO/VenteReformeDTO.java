package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.diafarms.ml.models.VenteReforme;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class VenteReformeDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private String magasinUniqueId;
    private String magasinNom;
    private Integer nombreSujets;
    private Double prixUnitaire;
    private Double montant;
    private Double montantRapporte;
    private String creeParNom;
    private LocalDateTime createdAt;
    private List<VenteReformeRepartitionDTO> repartitions;

    public static VenteReformeDTO fromEntity(VenteReforme v) {
        if (v == null) return null;

        return VenteReformeDTO.builder()
                .uniqueId(v.getUniqueId())
                .date(v.getDate())
                .heure(v.getHeure())
                .magasinUniqueId(v.getMagasin() != null ? v.getMagasin().getUniqueId() : null)
                .magasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null)
                .nombreSujets(v.getNombreSujets())
                .prixUnitaire(v.getPrixUnitaire())
                .montant(v.getMontant())
                .montantRapporte(v.getMontantRapporte())
                .creeParNom(v.getCreePar() != null ? v.getCreePar().getFullName() : null)
                .createdAt(v.getInitialisation() != null ? v.getInitialisation().getCreatedAt() : null)
                .repartitions(v.getRepartitions() != null ? v.getRepartitions().stream()
                        .map(VenteReformeRepartitionDTO::fromEntity)
                        .toList() : java.util.Collections.emptyList())
                .build();
    }
}
