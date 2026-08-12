package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.models.Facture;

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
public class FactureDTO {
    private String uniqueId;
    private String numeroFacture;
    private String clientUniqueId;
    private String clientNom;
    private LocalDate dateEmission;
    private String sourceType;
    private String sourceUniqueId;
    private String description;
    private Integer quantite;
    private Double prixUnitaire;
    private Double montantTotal;
    private Double montantPaye;
    private String statut;
    private String creeParNom;
    private LocalDateTime createdAt;

    public static FactureDTO fromEntity(Facture f) {
        if (f == null) return null;
        return FactureDTO.builder()
                .uniqueId(f.getUniqueId())
                .numeroFacture(f.getNumeroFacture())
                .clientUniqueId(f.getClient() != null ? f.getClient().getUniqueId() : null)
                .clientNom(f.getClient() != null ? f.getClient().getNom() : null)
                .dateEmission(f.getDateEmission())
                .sourceType(f.getSourceType() != null ? f.getSourceType().name() : null)
                .sourceUniqueId(f.getSourceUniqueId())
                .description(f.getDescription())
                .quantite(f.getQuantite())
                .prixUnitaire(f.getPrixUnitaire())
                .montantTotal(f.getMontantTotal())
                .montantPaye(f.getMontantPaye())
                .statut(f.getStatut() != null ? f.getStatut().name() : null)
                .creeParNom(f.getCreePar() != null ? f.getCreePar().getFullName() : null)
                .createdAt(f.getInitialisation() != null ? f.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
