package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.enums.Objectif;
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
public class ProjetsSelect {

     private Long id;
     private String uniqueId;
     private String code;
     private String titre;
     private LocalDate debut;
     private LocalDate finPrevue;
     private boolean active; // !initialisation.archive — le vrai statut actif/archivé, pas une date
     private Objectif objectif; // pour adapter les saisies proposées côté mobile (ex: pas de collecte d'œufs sur un projet REFORME)

     public static ProjetsSelect selectEntity(Projets data) {
        if (data == null) {
                return null;
        }

        return ProjetsSelect.builder()
                .id(data.getId())
                .uniqueId(data.getUniqueId())
                .code(data.getCode())
                .titre(data.getTitre())
                .debut(data.getDebut())
                .finPrevue(data.getFinPrevue())
                .active(data.getInitialisation() == null || !Boolean.TRUE.equals(data.getInitialisation().getArchive()))
                .objectif(data.getObjectif())
                .build();
    }
    
}
