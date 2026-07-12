package com.diafarms.ml.request.update;


import lombok.Data;
import java.time.LocalDate;

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
}