package com.diafarms.ml.DTO;

import java.time.LocalDate;
import java.time.LocalDateTime;

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
    private Double prixUnitaireEstime;
    private Double montantEstime;
    private Double montantAcompte;
    private LocalDate dateCommande;
    private LocalDate dateLivraisonPrevue;
    private String statut;
    private String venteUniqueId;
    private String creeParNom;
    private LocalDateTime createdAt;

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
                .prixUnitaireEstime(c.getPrixUnitaireEstime())
                .montantEstime(c.getMontantEstime())
                .montantAcompte(c.getMontantAcompte())
                .dateCommande(c.getDateCommande())
                .dateLivraisonPrevue(c.getDateLivraisonPrevue())
                .statut(c.getStatut() != null ? c.getStatut().name() : null)
                .venteUniqueId(c.getVenteUniqueId())
                .creeParNom(c.getCreePar() != null ? c.getCreePar().getFullName() : null)
                .createdAt(c.getInitialisation() != null ? c.getInitialisation().getCreatedAt() : null)
                .build();
    }
}
