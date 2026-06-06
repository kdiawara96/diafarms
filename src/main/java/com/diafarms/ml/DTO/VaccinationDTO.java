package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Vaccination;
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
public class VaccinationDTO {
    
    private Long id;
    private String uniqueId;
    private String nomVaccin;
    private Integer quantite;
    private Double prixUnitaire;
    private Double coutTotal;
    private String modeAdministration;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;


      public static VaccinationDTO fromEntity(Vaccination data) {
        if (data == null) {
            return null;
        }

        return VaccinationDTO.builder()
                .id(data.getId())
                .uniqueId(data.getUniqueId())
                .nomVaccin(data.getNomVaccin())
                .quantite(data.getQuantite())
                .prixUnitaire(data.getPrixUnitaire())
                .coutTotal(data.getCoutTotal())
                .modeAdministration(data.getModeAdministration())
                .createdAt(data.getInitialisation().getCreatedAt())
                .build();
      }
}
