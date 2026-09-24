package com.diafarms.ml.DTO;

import java.time.LocalDate;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Une ligne de l'historique d'un client (voir ClientReportDTO) — vente d'œufs ou de
// réforme, paiement ou remboursement, unifiées ici pour l'affichage chronologique.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ClientVenteLigneDTO {
    private String uniqueId;
    private LocalDate date;
    private String type; // "OEUFS", "REFORME", "PAIEMENT" ou "REMBOURSEMENT"
    private String magasinNom;
    private Double montant; // théorique (vente) ; négatif pour un remboursement
    private Double montantRapporte; // null = pas d'écart déclaré pour cette vente

    // Lignes "vente" (OEUFS/REFORME) : ce qui a déjà été imputé dessus, voir
    // CompteClientService.payeVente/resteAPayerVente.
    private Double paye;
    private Double resteAPayer;
    private String statutPaiement; // "PAYEE" | "PARTIELLE" | "NON_PAYEE"

    // Lignes "PAIEMENT"/"REMBOURSEMENT".
    private String mode; // ModePaiement
    private String origine; // OriginePaiement, paiements seulement
    private String statut; // StatutMouvement (ACTIF/ANNULE)
    private String commandeUniqueId;
}
