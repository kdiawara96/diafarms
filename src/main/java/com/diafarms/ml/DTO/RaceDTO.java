package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.Race;
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
public class RaceDTO {
    private Long id;
    private String uniqueId;
    private String nom;
     private String identifiant; // Un identifiant court et lisible pour les utilisateurs (ex: RAC-001, RAC-002, etc.)
    private String type;
    private String origine;
    private String description;
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    private Integer esperanceVieAnnees;
    private Double poidsAdulteKg;
    private Integer productionOeufsAn;
    private String couleurOeuf;
    private String tempsCroissance;
    private String poidsAbattage;
    private String rusticite;
    private String adaptationClimat;
    private String certificationRace;

        public static RaceDTO fromEntity(Race data) {
            if (data == null) {
                return null;
            }
    
            return RaceDTO.builder()
                    .id(data.getId())
                    .uniqueId(data.getUniqueId())
                    .nom(data.getNom())
                    .identifiant(data.getIdentifiant())
                    .type(data.getType().getValue())
                    .origine(data.getOrigine())
                    .description(data.getDescription())
                    .createdAt(data.getInitialisation().getCreatedAt())
                    .esperanceVieAnnees(data.getEsperanceVieAnnees())
                    .poidsAdulteKg(data.getPoidsAdulteKg())
                    .productionOeufsAn(data.getProductionOeufsAn())
                    .couleurOeuf(data.getCouleurOeuf().getValue())
                    .tempsCroissance(data.getTempsCroissance().getLabel())
                    .poidsAbattage(data.getPoidsAbattage().getLabel())
                    .rusticite(data.getRusticite().getLabel())
                    .adaptationClimat(data.getAdaptationClimat().getLabel())
                    .certificationRace(data.getCertificationRace().getLabel())
                    .build();
        }


        public static RaceDTO fromSelect(Race data) {
            if (data == null) {
                return null;
            }
    
            return RaceDTO.builder()
                    .id(data.getId())
                    .uniqueId(data.getUniqueId())
                    .nom(data.getNom())
                    .identifiant(data.getIdentifiant())
                    .build();
        }
}
