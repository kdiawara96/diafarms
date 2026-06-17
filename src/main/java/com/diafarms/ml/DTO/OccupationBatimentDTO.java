package com.diafarms.ml.DTO;


import java.time.LocalDate;

import com.diafarms.ml.models.OccupationBatiment;
import com.diafarms.ml.models.Projets;

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
public class OccupationBatimentDTO {
    
    private Long id;
    private LocalDate dateEntree;
    private LocalDate dateSortie;
    private Integer nbSujetsDansBatiment;

    private String nomBatiment;
    private String typeBatiment;

    public static OccupationBatimentDTO fromEntityList(OccupationBatiment data) {
        if (data == null) {
            return null;
        }

        return OccupationBatimentDTO.builder()
                .id(data.getId())
                .dateEntree(data.getDateEntree())
                .dateSortie(data.getDateSortie())
                .nbSujetsDansBatiment(data.getNbSujetsDansBatiment())
                .nomBatiment(data.getBatiment().getNom())
                .typeBatiment(data.getBatiment().getType().getValue())
                .build();
    }
}
