package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

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
    private Integer nombreSujets;
    private Double prixUnitaire;
    private Double montant;
    private String projetCode;
    private String projetUniqueId;
    private String batimentNom;
    private String batimentUniqueId;
    private LocalDateTime createdAt;

    public static VenteReformeDTO fromEntity(VenteReforme v) {
        if (v == null) return null;

        return VenteReformeDTO.builder()
                .uniqueId(v.getUniqueId())
                .date(v.getDate())
                .heure(v.getHeure())
                .nombreSujets(v.getNombreSujets())
                .prixUnitaire(v.getPrixUnitaire())
                .montant(v.getMontant())
                .projetCode(v.getProjet() != null ? v.getProjet().getCode() : null)
                .projetUniqueId(v.getProjet() != null ? v.getProjet().getUniqueId() : null)
                .batimentNom(v.getBatiment() != null ? v.getBatiment().getNom() : null)
                .batimentUniqueId(v.getBatiment() != null ? v.getBatiment().getUniqueId() : null)
                .createdAt(v.getInitialisation() != null ? v.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
