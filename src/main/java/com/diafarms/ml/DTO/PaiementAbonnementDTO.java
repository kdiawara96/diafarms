package com.diafarms.ml.DTO;

import java.time.LocalDateTime;

import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.PaiementAbonnement;
import com.diafarms.ml.models.Utilisateurs;

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
    // Console SUPER_ADMIN : ferme concernée, et paiement reçu hors application (saisi
    // directement par le SUPER_ADMIN) plutôt que déclaré par la ferme.
    private String farmUniqueId;
    private boolean horsApplication;
    // Montant attendu et poules comptées au moment de la déclaration (null avant le prix
    // par poule, et pour un paiement saisi par le SUPER_ADMIN).
    private Double montantAttendu;
    private Integer poulesComptees;

    // Farm.nom est null par construction pour une ferme fraîchement inscrite (le nom
    // saisi à l'inscription est stocké sur Utilisateurs.farmName, jamais recopié sur
    // Farm tant que l'ADMIN n'a pas visité Paramètres → Identité de la ferme) — un
    // UUID brut n'aide personne à identifier la ferme dans le portail SUPER_ADMIN,
    // donc on retombe sur le nom saisi à l'inscription avant l'UUID en dernier
    // recours. Même logique que AbonnementServiceImpl.resoudreFarmNom.
    private static String resoudreFarmNom(Farm farm, Utilisateurs declarePar) {
        if (farm.getNom() != null) {
            return farm.getNom();
        }
        if (declarePar != null && declarePar.getFarmName() != null) {
            return declarePar.getFarmName();
        }
        return farm.getUniqueId();
    }

    public static PaiementAbonnementDTO fromEntity(PaiementAbonnement p) {
        if (p == null) return null;
        return PaiementAbonnementDTO.builder()
                .uniqueId(p.getUniqueId())
                .farmNom(p.getAbonnement() != null && p.getAbonnement().getFarm() != null
                        ? resoudreFarmNom(p.getAbonnement().getFarm(), p.getDeclarePar())
                        : null)
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
                .farmUniqueId(p.getAbonnement() != null && p.getAbonnement().getFarm() != null
                        ? p.getAbonnement().getFarm().getUniqueId() : null)
                .horsApplication(Boolean.TRUE.equals(p.getHorsApplication()))
                .montantAttendu(p.getMontantAttendu())
                .poulesComptees(p.getPoulesComptees())
                .build();
    }
}
