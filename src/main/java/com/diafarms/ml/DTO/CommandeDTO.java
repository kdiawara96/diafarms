package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import com.diafarms.ml.models.Commande;

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
public class CommandeDTO {
    private String uniqueId;
    private String clientUniqueId;
    private String clientNom;
    private String magasinUniqueId;
    private String magasinNom;
    private String type;
    private Integer quantite;
    // Cumul déjà livré et ce qu'il reste — voir Commande.quantiteLivree/CommandeServiceImpl.livrer.
    private Integer quantiteLivree;
    private Integer quantiteRestante;
    private Double prixUnitaireEstime;
    private Double montantEstime;
    // Historique du tout premier acompte versé à la création — plus jamais réécrit
    // ensuite, voir CommandeServiceImpl.update (un acompte supplémentaire est un
    // paiement à part entière, pas un nouveau montantAcompte).
    private Double montantAcompte;
    private LocalDate dateCommande;
    private LocalDate dateLivraisonPrevue;
    private String statut;
    // Historique legacy : dernière vente créée par une conversion pré-refonte
    // (une seule livraison possible avant ce changement). Plus jamais écrit depuis
    // livrer() — une commande peut désormais avoir plusieurs livraisons, voir
    // le champ `livraisons` ci-dessous pour la liste à jour.
    private String venteUniqueId;
    private String creeParNom;
    private LocalDateTime createdAt;

    // Champs enrichis — calculés par CommandeServiceImpl.enrichir(Commande), jamais
    // stockés : reflètent l'état réel de l'argent/des livraisons de cette commande.
    private Double montantLivre; // Σ montants des ventes actives de la commande
    private Double acompteRecu; // Σ paiements ACTIFS d'origine ACOMPTE de la commande
    private Double payeSurCommande; // Σ imputations actives sur ses ventes livrées
    private Double resteAPayerLivre; // montantLivre - payeSurCommande
    private Integer resteALivrer; // quantite - quantiteLivree
    private List<LivraisonDTO> livraisons;
    private String motifFin;
    private String statutLibelle;

    @Getter
    @Setter
    @AllArgsConstructor
    @NoArgsConstructor
    @Builder
    public static class LivraisonDTO {
        private String venteUniqueId;
        private LocalDate date;
        private Integer quantite;
        private Double montant;
        private Double paye;
        private String statutPaiement; // "PAYEE" | "PARTIELLE" | "NON_PAYEE"
    }

    public static CommandeDTO fromEntity(Commande c) {
        if (c == null) return null;
        return CommandeDTO.builder()
                .uniqueId(c.getUniqueId())
                .clientUniqueId(c.getClient() != null ? c.getClient().getUniqueId() : null)
                .clientNom(c.getClient() != null ? c.getClient().getNom() : null)
                .magasinUniqueId(c.getMagasin() != null ? c.getMagasin().getUniqueId() : null)
                .magasinNom(c.getMagasin() != null ? c.getMagasin().getNom() : null)
                .type(c.getType() != null ? c.getType().name() : null)
                .quantite(c.getQuantite())
                .quantiteLivree(c.getQuantiteLivree() != null ? c.getQuantiteLivree() : 0)
                .quantiteRestante(c.getQuantite() != null
                        ? c.getQuantite() - (c.getQuantiteLivree() != null ? c.getQuantiteLivree() : 0)
                        : null)
                .prixUnitaireEstime(c.getPrixUnitaireEstime())
                .montantEstime(c.getMontantEstime())
                .montantAcompte(c.getMontantAcompte())
                .dateCommande(c.getDateCommande())
                .dateLivraisonPrevue(c.getDateLivraisonPrevue())
                .statut(c.getStatut() != null ? c.getStatut().name() : null)
                .venteUniqueId(c.getVenteUniqueId())
                .creeParNom(c.getCreePar() != null ? c.getCreePar().getFullName() : null)
                .createdAt(c.getInitialisation() != null ? c.getInitialisation().getCreatedAt() : null)
                .motifFin(c.getMotifFin())
                .build();
    }
}
