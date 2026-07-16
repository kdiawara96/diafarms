package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.diafarms.ml.models.VenteOeufs;

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
public class VenteOeufsDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private Integer quantiteOeufs;
    private Double prixUnitaire;
    private Double montant;
    private LocalDateTime createdAt;

    public static VenteOeufsDTO fromEntity(VenteOeufs v) {
        if (v == null) return null;

        return VenteOeufsDTO.builder()
                .uniqueId(v.getUniqueId())
                .date(v.getDate())
                .heure(v.getHeure())
                .quantiteOeufs(v.getQuantiteOeufs())
                .prixUnitaire(v.getPrixUnitaire())
                .montant(v.getMontant())
                .createdAt(v.getInitialisation() != null ? v.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
