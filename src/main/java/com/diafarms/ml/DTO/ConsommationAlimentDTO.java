package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.diafarms.ml.models.ConsommationAliment;

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
public class ConsommationAlimentDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private Double quantiteKg;
    private String projetCode;
    private String projetUniqueId;
    private String batimentNom;
    private String batimentUniqueId;
    private LocalDateTime createdAt;

    public static ConsommationAlimentDTO fromEntity(ConsommationAliment c) {
        if (c == null) return null;

        return ConsommationAlimentDTO.builder()
                .uniqueId(c.getUniqueId())
                .date(c.getDate())
                .heure(c.getHeure())
                .quantiteKg(c.getQuantiteKg())
                .projetCode(c.getProjet() != null ? c.getProjet().getCode() : null)
                .projetUniqueId(c.getProjet() != null ? c.getProjet().getUniqueId() : null)
                .batimentNom(c.getBatiment() != null ? c.getBatiment().getNom() : null)
                .batimentUniqueId(c.getBatiment() != null ? c.getBatiment().getUniqueId() : null)
                .createdAt(c.getInitialisation() != null ? c.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
