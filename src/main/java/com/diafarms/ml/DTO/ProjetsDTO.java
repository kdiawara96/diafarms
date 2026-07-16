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
     private Double chiffreAffaires; // CA réel = somme des transactions "entrée" validées du projet (voir fromEntity/fromEntityList)
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
     private Integer stockOeufsRestant;
     private Integer sujetsReformesCumulee;
     private Integer effectifVivant;


     /**
      * @param tauxPonte moyenne journalière récente d'œufs collectés / effectif
      *                   actuel (%), calculée par l'appelant (accès aux repos).
      * @param mortaliteCumulee morts cumulés / effectif initial (%), idem — sans ce
      *                   paramètre le champ restait null côté liste paginée des
      *                   projets (contrairement à fromEntity), d'où un "null" affiché
      *                   en clair par le front (ex: dialogue de clôture) au lieu d'un
      *                   pourcentage réel.
      * @param chiffreAffairesReel somme des transactions "entrée" validées du projet
      *                   (ventes d'œufs, vente réforme, etc.), calculée par l'appelant.
      *                   Remplace data.getChiffreAffaires() : cette colonne entité reste
      *                   figée à 0.0 depuis la création du projet (jamais recalculée),
      *                   donc plus une vraie donnée de chiffre d'affaires.
      * @param stockOeufsRestant œufs collectés - cassés - vendus (VenteOeufs), calculé
      *                   par l'appelant.
      * @param sujetsReformesCumulee sujets vendus en réforme (VenteReforme), idem.
      * @param effectifVivant nbSujets initial - mortalité - sujetsReformesCumulee, idem.
      */
     public static ProjetsDTO fromEntityList(Projets data, Double tauxPonte, Double mortaliteCumulee, Double chiffreAffairesReel,
                                              Integer stockOeufsRestant, Integer sujetsReformesCumulee, Integer effectifVivant) {
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
                .chiffreAffaires(chiffreAffairesReel)
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
                .tauxPonte(tauxPonte)
                .mortaliteCumulee(mortaliteCumulee)
                .stockOeufsRestant(stockOeufsRestant)
                .sujetsReformesCumulee(sujetsReformesCumulee)
                .effectifVivant(effectifVivant)
                .build();
    }



       public static ProjetsDTO fromEntity(Projets data) {
        return fromEntity(data, 0.0, 0.0, 0.0, 0, 0, 0);
       }

       /**
        * @param tauxPonte moyenne journalière récente d'œufs collectés / effectif
        *                   actuel (%), calculée par l'appelant (accès aux repos).
        * @param mortaliteCumulee morts cumulés / effectif initial (%), idem.
        * @param chiffreAffairesReel voir fromEntityList — même remplacement de
        *                   data.getChiffreAffaires() par la somme réelle des ventes.
        * @param stockOeufsRestant œufs collectés - cassés - vendus, voir fromEntityList.
        * @param sujetsReformesCumulee sujets vendus en réforme, voir fromEntityList.
        * @param effectifVivant nbSujets initial - mortalité - sujetsReformesCumulee.
        */
       public static ProjetsDTO fromEntity(Projets data, Double tauxPonte, Double mortaliteCumulee, Double chiffreAffairesReel,
                                            Integer stockOeufsRestant, Integer sujetsReformesCumulee, Integer effectifVivant) {
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
                .chiffreAffaires(chiffreAffairesReel)
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

                // .fichiersMedia(data.getFichiers() != null ? data.getFichiers().stream()
                //         .map(FichierMediaDTO::fromEntity)
                //         .toList() : java.util.Collections.emptyList())
                .tauxPonte(tauxPonte)
                .mortaliteCumulee(mortaliteCumulee)
                .stockOeufsRestant(stockOeufsRestant)
                .sujetsReformesCumulee(sujetsReformesCumulee)
                .effectifVivant(effectifVivant)
                .build();
        }


}
