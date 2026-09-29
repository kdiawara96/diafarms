package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.models.AffectationPersonnel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AffectationPersonnelDTO {
    private String uniqueId;
    private String projetUniqueId;
    private String projetCode;
    private String projetTitre;
    private LocalDate dateDebut;
    private LocalDate dateFin;

    public static AffectationPersonnelDTO fromEntity(AffectationPersonnel a) {
        return AffectationPersonnelDTO.builder()
                .uniqueId(a.getUniqueId())
                .projetUniqueId(a.getProjet().getUniqueId())
                .projetCode(a.getProjet().getCode())
                .projetTitre(a.getProjet().getTitre())
                .dateDebut(a.getDateDebut())
                .dateFin(a.getDateFin())
                .build();
    }
}
