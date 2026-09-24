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

    // Circuit argent client (voir TransactionServiceImpl.getStats) : vue ferme entière
    // uniquement (vueParProjet = false) — sinon vendu seul est renseigné (théorique
    // œufs+réforme) et les autres valent 0, faute de pouvoir scoper encaissé/remboursé/
    // dû/avances par projet (paiements et remboursements clients ne sont pas rattachés
    // à un projet précis).
    private Double totalVendu;
    private Double totalEncaisse;
    private Double totalRembourse;
    private Double totalDuClients;
    private Double totalAvancesClients;
    private boolean vueParProjet;
}
