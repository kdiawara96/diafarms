package com.diafarms.ml.request.create;


import lombok.Data;
import java.time.LocalDate;
import java.util.List;

@Data
public class InvestissementRequest {
    private String categorie;
    private String nom;
    private Double montant;
    private LocalDate dateAchat;
    private String fournisseur;
    private Integer dureeAmortissement;
    private String affectation; // "COMMUN" ou "DEDIE"
    private String commentaire;
    private String type; // "Lineaire"
    private String projetId; // 🟢 Reçoit l'UUID du projet (uniqueId)
    // Poulaillers existants à relier (uniqueId).
    private List<String> batimentIds;
    // Poulaillers à créer dans la même transaction que l'investissement.
    private List<NouveauPoulaillerRequest> nouveauxPoulaillers;
}