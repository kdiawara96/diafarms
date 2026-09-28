package com.diafarms.ml.DTO;


import com.diafarms.ml.enums.TypeAffectation;
import lombok.*;
import java.time.LocalDate;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvestissementDTO {
    private String uniqueId;
    private String categorie;
    private String nom;
    private String icon;
    private Double montant;
    private LocalDate dateAchat;
    private String fournisseur;
    private String type;
    private Integer dureeAmortissement;
    private TypeAffectation affectation;
    private String commentaire;
    private Double amortiCumule;
    private Double amortissementMensuel; // Champ calculé
    private Double valeurNette;        // Champ calculé
    private List<InvestissementRepartitionDTO> repartitions;
    // Poulaillers reliés (détails modifiables uniquement dans Poulaillers).
    private List<PoulaillerLie> batiments;
    // Projets ayant occupé ces poulaillers (lecture seule, déduit des occupations ;
    // n'a aucun effet sur les répartitions).
    private List<String> projetsUtilisateurs;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PoulaillerLie {
        private String uniqueId;
        private String nom;
        private Integer capacite;
        private Double superficieM2;
    }
}