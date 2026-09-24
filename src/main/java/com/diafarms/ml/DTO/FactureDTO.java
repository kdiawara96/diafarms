package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.diafarms.ml.commons.CalculImputation;
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
    private Double resteAPayer;
    private String statut;
    private Boolean legacy;
    private String motifAnnulation;
    private List<FactureLigneDTO> lignes;
    private String creeParNom;
    private LocalDateTime createdAt;

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }

    // Construit le DTO à partir de l'entité, des lignes déjà chargées (DTO) et du
    // montant payé déjà calculé par l'appelant (FactureServiceImpl.toDto) :
    // Σ min(ligne.montant, CompteClientService.payeVente(ligne)) pour une facture
    // non-legacy — plafonné ligne par ligne pour qu'une avance imputée au-delà du
    // montant d'une ligne ne gonfle pas le total payé de la facture. Une facture
    // legacy garde son montantPaye historique tel quel (colonne Facture.montantPaye,
    // jamais recalculé). Le statut ANNULEE prime toujours sur le calcul PAYEE/
    // PARTIELLE/IMPAYEE (voir Facture.StatutFacture).
    public static FactureDTO fromEntity(Facture f, List<FactureLigneDTO> lignes, double payeCalcule) {
        if (f == null) return null;

        boolean legacy = Boolean.TRUE.equals(f.getLegacy());
        double montantTotal = nz(f.getMontantTotal());
        double montantPaye = legacy ? nz(f.getMontantPaye()) : payeCalcule;
        double resteAPayer = CalculImputation.arrondi(montantTotal - montantPaye);

        String statut;
        if (f.getStatut() == Facture.StatutFacture.ANNULEE) {
            statut = Facture.StatutFacture.ANNULEE.name();
        } else if (montantPaye >= montantTotal) {
            statut = Facture.StatutFacture.PAYEE.name();
        } else if (montantPaye > 0) {
            statut = Facture.StatutFacture.PARTIELLE.name();
        } else {
            statut = Facture.StatutFacture.IMPAYEE.name();
        }

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
                .montantPaye(montantPaye)
                .resteAPayer(resteAPayer)
                .statut(statut)
                .legacy(f.getLegacy())
                .motifAnnulation(f.getMotifAnnulation())
                .lignes(lignes)
                .creeParNom(f.getCreePar() != null ? f.getCreePar().getFullName() : null)
                .createdAt(f.getInitialisation() != null ? f.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
