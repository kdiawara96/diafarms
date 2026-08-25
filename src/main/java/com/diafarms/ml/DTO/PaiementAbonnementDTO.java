package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.PaiementAbonnement;

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
public class PaiementAbonnementDTO {
    private String uniqueId;
    private String farmNom;
    private Double montant;
    private String periodicite;
    private String moyenPaiement;
    private String reference;
    private String statut;
    private LocalDateTime dateDeclaration;
    private String declareParNom;
    private LocalDateTime dateValidation;
    private String valideParNom;
    private String motifRejet;

    public static PaiementAbonnementDTO fromEntity(PaiementAbonnement p) {
        if (p == null) return null;
        return PaiementAbonnementDTO.builder()
                .uniqueId(p.getUniqueId())
                .farmNom(p.getAbonnement() != null && p.getAbonnement().getFarm() != null
                        ? p.getAbonnement().getFarm().getNom() : null)
                .montant(p.getMontant())
                .periodicite(p.getPeriodicite() != null ? p.getPeriodicite().name() : null)
                .moyenPaiement(p.getMoyenPaiement())
                .reference(p.getReference())
                .statut(p.getStatut() != null ? p.getStatut().name() : null)
                .dateDeclaration(p.getDateDeclaration())
                .declareParNom(p.getDeclarePar() != null ? p.getDeclarePar().getFullName() : null)
                .dateValidation(p.getDateValidation())
                .valideParNom(p.getValidePar() != null ? p.getValidePar().getFullName() : null)
                .motifRejet(p.getMotifRejet())
                .build();
    }
}
