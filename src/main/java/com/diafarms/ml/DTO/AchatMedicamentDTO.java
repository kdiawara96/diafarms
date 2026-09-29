package com.diafarms.ml.DTO;

import java.time.LocalDate;

import com.diafarms.ml.models.AchatMedicament;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AchatMedicamentDTO {
    private String uniqueId;
    private String nom;
    private String forme;
    private String unite;
    private Double quantite;
    private Double prixUnitaire;
    private Double coutTotal;
    private LocalDate dateAchat;
    private String fournisseur;
    private String observations;
    private String projetUniqueId;
    private String projetCode;
    private String batimentUniqueId;
    private String batimentNom;

    public static AchatMedicamentDTO fromEntity(AchatMedicament a) {
        return AchatMedicamentDTO.builder()
                .uniqueId(a.getUniqueId())
                .nom(a.getNom())
                .forme(a.getForme() != null ? a.getForme().name() : null)
                .unite(a.getUnite())
                .quantite(a.getQuantite())
                .prixUnitaire(a.getPrixUnitaire())
                .coutTotal(a.getCoutTotal())
                .dateAchat(a.getDateAchat())
                .fournisseur(a.getFournisseur())
                .observations(a.getObservations())
                .projetUniqueId(a.getProjet().getUniqueId())
                .projetCode(a.getProjet().getCode())
                .batimentUniqueId(a.getBatiment() != null ? a.getBatiment().getUniqueId() : null)
                .batimentNom(a.getBatiment() != null ? a.getBatiment().getNom() : null)
                .build();
    }
}
