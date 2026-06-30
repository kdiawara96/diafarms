package com.diafarms.ml.DTO;

import lombok.*;
import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestissementRepartitionDTO {
    private Long id;
    private String codeProjet; // Correspond à l'uniqueId du projet (ex: "P-001")
    private String titreProjet;
    private LocalDate dateDebut;
    private LocalDate dateFin;
    private Integer moisUtilises;
    private Double montantAlloue;
}