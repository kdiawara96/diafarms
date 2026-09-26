package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import com.diafarms.ml.enums.TypeVenteReforme;
import com.diafarms.ml.models.VenteReforme;

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
public class VenteReformeDTO {

    private String uniqueId;
    private LocalDate date;
    private LocalTime heure;
    private String magasinUniqueId;
    private String magasinNom;
    private String clientUniqueId;
    private String clientNom;
    private Integer nombreSujets;
    private Double prixUnitaire;
    private Double montant;
    private Double montantRapporte;
    private String typeVente;
    private Double poidsTotalKg;
    // Calculés, jamais stockés (voir les helpers statiques plus bas) :
    // poidsMoyenParSujet = poidsTotalKg / nombreSujets (KILO seulement, 3 décimales) ;
    // prixParKg = montant / poidsTotalKg (KILO seulement) ; prixParTete = montant /
    // nombreSujets (toute vente) — équivalents comparables entre ventes TETE et KILO.
    private Double poidsMoyenParSujet;
    private Double prixParKg;
    private Double prixParTete;
    // Non null = livraison de cette commande.
    private String commandeUniqueId;
    private String creeParNom;
    private LocalDateTime createdAt;
    // Non null = suppression en attente de validation par un admin/responsable — voir
    // VenteReformeImpl.demanderSuppression.
    private String demandeSuppressionParNom;
    private LocalDateTime dateDemandeSuppression;
    private String motifSuppression;
    private List<VenteReformeRepartitionDTO> repartitions;

    private static boolean auKilo(VenteReforme v) {
        return v.getTypeVente() == TypeVenteReforme.KILO && v.getPoidsTotalKg() != null && v.getPoidsTotalKg() > 0;
    }

    public static Double poidsMoyenParSujet(VenteReforme v) {
        if (!auKilo(v) || v.getNombreSujets() == null || v.getNombreSujets() <= 0) return null;
        return Math.round(v.getPoidsTotalKg() / v.getNombreSujets() * 1000.0) / 1000.0;
    }

    public static Double prixParKg(VenteReforme v) {
        if (!auKilo(v) || v.getMontant() == null) return null;
        return Math.round(v.getMontant() / v.getPoidsTotalKg() * 100.0) / 100.0;
    }

    public static Double prixParTete(VenteReforme v) {
        if (v.getMontant() == null || v.getNombreSujets() == null || v.getNombreSujets() <= 0) return null;
        return Math.round(v.getMontant() / v.getNombreSujets() * 100.0) / 100.0;
    }

    public static VenteReformeDTO fromEntity(VenteReforme v) {
        if (v == null) return null;

        return VenteReformeDTO.builder()
                .uniqueId(v.getUniqueId())
                .date(v.getDate())
                .heure(v.getHeure())
                .magasinUniqueId(v.getMagasin() != null ? v.getMagasin().getUniqueId() : null)
                .magasinNom(v.getMagasin() != null ? v.getMagasin().getNom() : null)
                .clientUniqueId(v.getClient() != null ? v.getClient().getUniqueId() : null)
                .clientNom(v.getClient() != null ? v.getClient().getNom() : null)
                .nombreSujets(v.getNombreSujets())
                .prixUnitaire(v.getPrixUnitaire())
                .montant(v.getMontant())
                .montantRapporte(v.getMontantRapporte())
                .typeVente(v.getTypeVente() != null ? v.getTypeVente().name() : null)
                .poidsTotalKg(v.getPoidsTotalKg())
                .poidsMoyenParSujet(poidsMoyenParSujet(v))
                .prixParKg(prixParKg(v))
                .prixParTete(prixParTete(v))
                .commandeUniqueId(v.getCommande() != null ? v.getCommande().getUniqueId() : null)
                .creeParNom(v.getCreePar() != null ? v.getCreePar().getFullName() : null)
                .createdAt(v.getInitialisation() != null ? v.getInitialisation().getCreatedAt() : null)
                .demandeSuppressionParNom(v.getDemandeSuppressionPar() != null ? v.getDemandeSuppressionPar().getFullName() : null)
                .dateDemandeSuppression(v.getDateDemandeSuppression())
                .motifSuppression(v.getMotifSuppression())
                .repartitions(v.getRepartitions() != null ? v.getRepartitions().stream()
                        .map(VenteReformeRepartitionDTO::fromEntity)
                        .toList() : java.util.Collections.emptyList())
                .build();
    }
}
