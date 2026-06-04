package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.models.Alimentation;
import com.fasterxml.jackson.annotation.JsonFormat;

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
public class AlimentationDTO {
    
    private Long id;
    private String uniqueId;
    private String nomAliment;
    private Double sac;
    private Double quantiteKg;
    private Double coutTotal;
    private LocalDate dateDistribution;
    private String observations;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;

       public static AlimentationDTO fromEntityList(Alimentation data) {
        
        if (data == null) {
            return null;
        }

        return AlimentationDTO.builder()
                .id(data.getId())
                .uniqueId(data.getUniqueId())
                .nomAliment(data.getNomAliment())
                .sac(data.getSac())
                .quantiteKg(data.getQuantiteKg())
                .coutTotal(data.getCoutTotal())
                .dateDistribution(data.getDateDistribution())
                .observations(data.getObservations())
                .createdAt(data.getInitialisation().getCreatedAt())
                .build();
    }
}
