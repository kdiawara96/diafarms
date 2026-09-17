package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.diafarms.ml.models.Entretien;

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
public class EntretienDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private String niveau; // "BATIMENT" | "SITE"
    private String type; // "NETTOYAGE" | "COPEAU" | "AUTRE"
    private String description;
    private String observations;
    private String batimentNom;
    private String batimentUniqueId;
    private LocalDateTime createdAt;

    public static EntretienDTO fromEntity(Entretien e) {
        if (e == null) return null;

        return EntretienDTO.builder()
                .uniqueId(e.getUniqueId())
                .date(e.getDate())
                .heure(e.getHeure())
                .niveau(e.getNiveau() != null ? e.getNiveau().name() : null)
                .type(e.getType() != null ? e.getType().name() : null)
                .description(e.getDescription())
                .observations(e.getObservations())
                .batimentNom(e.getBatiment() != null ? e.getBatiment().getNom() : null)
                .batimentUniqueId(e.getBatiment() != null ? e.getBatiment().getUniqueId() : null)
                .createdAt(e.getInitialisation() != null ? e.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
