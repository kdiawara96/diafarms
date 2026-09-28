package com.diafarms.ml.request.update;


import lombok.Data;
import com.diafarms.ml.request.create.NouveauPoulaillerRequest;
import java.time.LocalDate;
import java.util.List;

@Data
public class InvestissementUpdateRequestDTO {
    private String categorie;
    private String nom;
    private Double montant;
    private LocalDate dateAchat;
    private String fournisseur;
    private Integer dureeAmortissement;
    private String affectation; // "COMMUN" ou "DEDIE"
    private String commentaire;
    private String type; // "Lineaire"
    private String projetId; // Optionnel, pour changer le projet associé
    // Poulaillers existants à relier (uniqueId). En modification : la liste complète
    // des liens voulus (null = liens inchangés).
    private List<String> batimentIds;
    // Poulaillers à créer dans la même transaction que l'investissement.
    private List<NouveauPoulaillerRequest> nouveauxPoulaillers;
}