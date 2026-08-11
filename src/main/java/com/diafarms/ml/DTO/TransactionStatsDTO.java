package com.diafarms.ml.DTO;

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
public class TransactionStatsDTO {
    private long nbValide;
    private long nbAttente;
    private long nbRejete;
    private Double totalEntreesValidees;
    private Double totalSortiesValidees;
    private Double totalVenteOeufs;
    private Double totalVenteReforme;
    // Ferme entière, ignore le scope projet/comptable (voir TransactionServiceImpl.
    // getStats) : le montant réellement rapporté par les vendeurs (pas le théorique
    // quantité×prix déjà dans totalVenteOeufs/totalVenteReforme) et la dette vendeur
    // cumulée en cours — servent au web à afficher "Total entrées" en distinguant
    // théorique/réel plutôt que de sommer aveuglément le théorique comme du cash réel.
    private Double totalMontantRecuVentes;
    private Double totalDuParVendeurs;
    // Même principe que totalDuParVendeurs, mais pour les ventes à crédit imputées à
    // un client identifié plutôt qu'au vendeur (voir SoldeClient, Option A retenue
    // dans ROADMAP_CLIENTS_COMMANDES_FACTURATION.md).
    private Double totalDuParClients;
}
