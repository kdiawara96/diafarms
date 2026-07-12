package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.diafarms.ml.models.Mortalite;

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
public class MortaliteDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private Integer nombreMorts;
    private String cause;
    private String projetCode;
    private String projetUniqueId;
    private String batimentNom;
    private String batimentUniqueId;
    private LocalDateTime createdAt;

    public static MortaliteDTO fromEntity(Mortalite m) {
        if (m == null) return null;

        return MortaliteDTO.builder()
                .uniqueId(m.getUniqueId())
                .date(m.getDate())
                .heure(m.getHeure())
                .nombreMorts(m.getNombreMorts())
                .cause(m.getCause())
                .projetCode(m.getProjet() != null ? m.getProjet().getCode() : null)
                .projetUniqueId(m.getProjet() != null ? m.getProjet().getUniqueId() : null)
                .batimentNom(m.getBatiment() != null ? m.getBatiment().getNom() : null)
                .batimentUniqueId(m.getBatiment() != null ? m.getBatiment().getUniqueId() : null)
                .createdAt(m.getInitialisation() != null ? m.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
