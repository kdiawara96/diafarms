package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.diafarms.ml.enums.ProduitVenteDiverse;
import com.diafarms.ml.models.VenteDiverse;

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
public class VenteDiverseDTO {
    private String uniqueId;
    private LocalDate date;
    private ProduitVenteDiverse produit;
    private Double quantite;
    private Double prixUnitaire;
    private Double montant;
    private String description;
    // « Cette vente concerne » : PROJET (projet ci-dessous) ou FERME.
    private String rattachement;
    private String projetUniqueId;
    private String projetCode;
    private String creeParNom;
    private LocalDateTime createdAt;
    private String demandeSuppressionParNom;
    private LocalDateTime dateDemandeSuppression;
    private String motifSuppression;

    public static VenteDiverseDTO fromEntity(VenteDiverse v) {
        if (v == null) return null;
        return VenteDiverseDTO.builder()
                .uniqueId(v.getUniqueId())
                .date(v.getDate())
                .produit(v.getProduit())
                .quantite(v.getQuantite())
                .prixUnitaire(v.getPrixUnitaire())
                .montant(v.getMontant())
                .description(v.getDescription())
                .rattachement(v.getProjet() != null ? "PROJET" : "FERME")
                .projetUniqueId(v.getProjet() != null ? v.getProjet().getUniqueId() : null)
                .projetCode(v.getProjet() != null ? v.getProjet().getCode() : null)
                .creeParNom(v.getCreePar() != null ? v.getCreePar().getFullName() : null)
                .createdAt(v.getInitialisation() != null ? v.getInitialisation().getCreatedAt() : null)
                .demandeSuppressionParNom(v.getDemandeSuppressionPar() != null ? v.getDemandeSuppressionPar().getFullName() : null)
                .dateDemandeSuppression(v.getDateDemandeSuppression())
                .motifSuppression(v.getMotifSuppression())
                .build();
    }
}
