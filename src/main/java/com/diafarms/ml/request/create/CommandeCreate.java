package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class CommandeCreate {
    private String clientUniqueId; // obligatoire — voir Commande.client
    private String magasinUniqueId; // obligatoire, magasin de vente destination
    private String type; // "OEUFS" ou "REFORME"
    private Integer quantite;
    private Double prixUnitaireEstime; // optionnel, informatif
    private Double montantEstime;
    private Double montantAcompte; // optionnel
    private String dateCommande; // optionnel, défaut = aujourd'hui
    private String dateLivraisonPrevue; // optionnel
}
