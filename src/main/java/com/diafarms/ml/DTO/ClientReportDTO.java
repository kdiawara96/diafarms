package com.diafarms.ml.DTO;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Rapport complet d'un client : combien acheté (théorique), combien payé (réel,
// COALESCE(montantRapporte, montant) par vente — voir VenteOeufsRepo.
// sumMontantRapporteByFarmIdAndDateRange pour le même raisonnement), combien il doit
// encore (solde, voir SoldeClient), et l'historique chronologique de ses achats.
@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class ClientReportDTO {
    private String clientUniqueId;
    private String clientNom;
    private double totalAchete; // théorique, somme de toutes les ventes (œufs + réforme)
    private double totalPaye; // réel, COALESCE(montantRapporte, montant) par vente
    private double solde; // positif = le client doit encore, voir SoldeClient
    private List<ClientVenteLigneDTO> historique;
}
