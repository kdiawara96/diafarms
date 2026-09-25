package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

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
public class SessionPeseeEvenementDTO {
    private String uniqueId;
    private String type;
    private String peseeUniqueId;
    private Integer ancienNombre;
    private Double ancienPoids;
    private Integer nouveauNombre;
    private Double nouveauPoids;
    private String description;
    private String parNom;
    private LocalDateTime date;
}
