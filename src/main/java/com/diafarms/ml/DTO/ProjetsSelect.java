package com.diafarms.ml.DTO;

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

     public static ProjetsSelect selectEntity(Projets data) {
        if (data == null) {
                return null;
        }

        return ProjetsSelect.builder()
                .id(data.getId())
                .uniqueId(data.getUniqueId())
                .code(data.getCode())
                .titre(data.getTitre())
                .build();
    }
    
}
