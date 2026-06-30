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
}