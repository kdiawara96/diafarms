package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

import com.diafarms.ml.models.CollecteOeufs;

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
public class CollecteOeufsDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private Integer oeufsCollectes;
    private Integer oeufsCasses;
    private Integer oeufsNonUtilisables;
    private String projetCode;
    private String projetUniqueId;
    private String batimentNom;
    private String batimentUniqueId;
    private String magasinStockageNom;
    private String magasinStockageUniqueId;
    private LocalDateTime createdAt;

    public static CollecteOeufsDTO fromEntity(CollecteOeufs c) {
        if (c == null) return null;

        return CollecteOeufsDTO.builder()
                .uniqueId(c.getUniqueId())
                .date(c.getDate())
                .heure(c.getHeure())
                .oeufsCollectes(c.getOeufsCollectes())
                .oeufsCasses(c.getOeufsCasses())
                .oeufsNonUtilisables(c.getOeufsNonUtilisables())
                .projetCode(c.getProjet() != null ? c.getProjet().getCode() : null)
                .projetUniqueId(c.getProjet() != null ? c.getProjet().getUniqueId() : null)
                .batimentNom(c.getBatiment() != null ? c.getBatiment().getNom() : null)
                .batimentUniqueId(c.getBatiment() != null ? c.getBatiment().getUniqueId() : null)
                .magasinStockageNom(c.getMagasinStockage() != null ? c.getMagasinStockage().getNom() : null)
                .magasinStockageUniqueId(c.getMagasinStockage() != null ? c.getMagasinStockage().getUniqueId() : null)
                .createdAt(c.getInitialisation() != null ? c.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
