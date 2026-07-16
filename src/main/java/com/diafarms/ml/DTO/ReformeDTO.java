package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.diafarms.ml.models.Reforme;

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
public class ReformeDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private Integer nombreSujets;
    private String cause;
    private String projetCode;
    private String projetUniqueId;
    private String batimentNom;
    private String batimentUniqueId;
    private LocalDateTime createdAt;

    public static ReformeDTO fromEntity(Reforme r) {
        if (r == null) return null;

        return ReformeDTO.builder()
                .uniqueId(r.getUniqueId())
                .date(r.getDate())
                .heure(r.getHeure())
                .nombreSujets(r.getNombreSujets())
                .cause(r.getCause())
                .projetCode(r.getProjet() != null ? r.getProjet().getCode() : null)
                .projetUniqueId(r.getProjet() != null ? r.getProjet().getUniqueId() : null)
                .batimentNom(r.getBatiment() != null ? r.getBatiment().getNom() : null)
                .batimentUniqueId(r.getBatiment() != null ? r.getBatiment().getUniqueId() : null)
                .createdAt(r.getInitialisation() != null ? r.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
