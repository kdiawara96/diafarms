package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.models.MagasinTransfert;

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
public class MagasinTransfertDTO {
    private String uniqueId;
    private String magasinUniqueId;
    private String magasinNom;
    private String projetUniqueId;
    private String projetCode;
    private String magasinStockageUniqueId;
    private String magasinStockageNom;
    private String type;
    private Integer quantite;
    private LocalDate date;
    private String creeParNom;

    public static MagasinTransfertDTO fromEntity(MagasinTransfert t) {
        if (t == null) return null;
        return MagasinTransfertDTO.builder()
                .uniqueId(t.getUniqueId())
                .magasinUniqueId(t.getMagasin() != null ? t.getMagasin().getUniqueId() : null)
                .magasinNom(t.getMagasin() != null ? t.getMagasin().getNom() : null)
                .projetUniqueId(t.getProjet() != null ? t.getProjet().getUniqueId() : null)
                .projetCode(t.getProjet() != null ? t.getProjet().getCode() : null)
                .magasinStockageUniqueId(t.getMagasinStockage() != null ? t.getMagasinStockage().getUniqueId() : null)
                .magasinStockageNom(t.getMagasinStockage() != null ? t.getMagasinStockage().getNom() : null)
                .type(t.getType() != null ? t.getType().name() : null)
                .quantite(t.getQuantite())
                .date(t.getDate())
                .creeParNom(t.getCreePar() != null ? t.getCreePar().getFullName() : null)
                .build();
    }
}
