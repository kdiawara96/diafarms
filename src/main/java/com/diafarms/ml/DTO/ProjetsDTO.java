package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.diafarms.ml.enums.Objectif;
import com.diafarms.ml.models.Projets;
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
public class ProjetsDTO {

     private Long id;
     private String uniqueId;
     private String code;
     private String titre;
     private String responsable;
     private String fournisseursPoussins;
     private LocalDate debut;
     private LocalDate finPrevue;
     private Integer nbSujets;
     private Double puSujet;
     private Double autresDepense;
     private Double caTotalSujets; // CA total = nbSujets × puSujet + autresDepense
     private Double chiffreAffaires; // CA prévu (chiffre d'affaires) = nb
     private Double margeNette;  
     private Objectif objectif;

     @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
     private LocalDateTime createdAt;

     private List<AlimentationDTO> alimentation;
     private RaceDTO race;
     private List<OccupationBatimentDTO> occupationBatiment;
     private List<VaccinationDTO> vaccination;
     private List<ProjectAlertConfigDTO> alertConfig;
     private List<FichierMediaDTO> fichiersMedia;

     private Double tauxPonte;
     private Double mortaliteCumulee;


     public static ProjetsDTO fromEntityList(Projets data) {
        if (data == null) {
            return null;
        }

        return ProjetsDTO.builder()
                .id(data.getId())
                .uniqueId(data.getUniqueId())
                .code(data.getCode())
                .titre(data.getTitre())
                .responsable(data.getResponsable())
                .fournisseursPoussins(data.getFournisseurs_poussins())
                .debut(data.getDebut())
                .finPrevue(data.getFinPrevue())
                .nbSujets(data.getNbSujets())
                .chiffreAffaires(data.getChiffreAffaires())
                .caTotalSujets(data.getCaTotalSujets())
                .margeNette(data.getMargeNette())
                .puSujet(data.getPuSujet())
                .autresDepense(data.getAutresDepense())
                .objectif(data.getObjectif())
                .createdAt(data.getInitialisation().getCreatedAt())
                .race(RaceDTO.fromEntity(data.getRace()))
                .occupationBatiment(data.getOccupations() != null ? data.getOccupations().stream()
                        .map(OccupationBatimentDTO::fromEntityList)
                        .toList() : null)
             
                .build();
    }



       public static ProjetsDTO fromEntity(Projets data) {
        if (data == null) {
                return null;
        }

        return ProjetsDTO.builder()
                .id(data.getId())
                .uniqueId(data.getUniqueId())
                .code(data.getCode())
                .titre(data.getTitre())
                .responsable(data.getResponsable())
                .fournisseursPoussins(data.getFournisseurs_poussins())
                .debut(data.getDebut())
                .finPrevue(data.getFinPrevue())
                .nbSujets(data.getNbSujets())
                .chiffreAffaires(data.getChiffreAffaires())
                .caTotalSujets(data.getCaTotalSujets())
                .margeNette(data.getMargeNette())
                .puSujet(data.getPuSujet())
                .autresDepense(data.getAutresDepense())
                .objectif(data.getObjectif())
                // Sécurité au cas où l'initialisation est nulle
                .createdAt(data.getInitialisation() != null ? data.getInitialisation().getCreatedAt() : null)
                
                .alimentation(data.getAlimentations() != null ? data.getAlimentations().stream()
                        .map(AlimentationDTO::fromEntityList)
                        .toList() : java.util.Collections.emptyList()) // Remplacer null par une liste vide est plus propre pour le Front
                
                .race(RaceDTO.fromEntity(data.getRace()))
                
                .occupationBatiment(data.getOccupations() != null ? data.getOccupations().stream()
                        .map(OccupationBatimentDTO::fromEntityList)
                        .toList() : java.util.Collections.emptyList())
                
                // CORRECTION ICI : Ajout de la sécurité anti-NullPointerException
                .vaccination(data.getVaccinations() != null ? data.getVaccinations().stream()
                        .map(VaccinationDTO::fromEntity)
                        .toList() : java.util.Collections.emptyList())
                
                .alertConfig(data.getAlertConfigs() != null ? data.getAlertConfigs().stream()
                        .map(ProjectAlertConfigDTO::fromEntity)
                        .toList() : java.util.Collections.emptyList())
                
                .fichiersMedia(data.getFichiers() != null ? data.getFichiers().stream()
                        .map(FichierMediaDTO::fromEntity)
                        .toList() : java.util.Collections.emptyList())
                .tauxPonte(0.0)
                .mortaliteCumulee(0.0)
                .build();
        }


}
