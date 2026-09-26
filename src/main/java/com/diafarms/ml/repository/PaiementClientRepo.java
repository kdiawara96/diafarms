package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.diafarms.ml.models.PaiementClient;

public interface PaiementClientRepo extends JpaRepository<PaiementClient, Long> {
    Optional<PaiementClient> findByUniqueId(String uniqueId);

    @Query("SELECT p FROM PaiementClient p LEFT JOIN FETCH p.commande WHERE p.client.id = :clientId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF ORDER BY p.date ASC, p.id ASC")
    List<PaiementClient> findActifsByClientId(@Param("clientId") Long clientId);

    @Query("SELECT p FROM PaiementClient p LEFT JOIN FETCH p.recuPar LEFT JOIN FETCH p.annulePar " +
           "WHERE p.client.id = :clientId ORDER BY p.date DESC, p.id DESC")
    List<PaiementClient> findAllByClientIdForHistorique(@Param("clientId") Long clientId);

    @Query("SELECT p FROM PaiementClient p WHERE p.commande.id = :commandeId ORDER BY p.date ASC, p.id ASC")
    List<PaiementClient> findByCommandeId(@Param("commandeId") Long commandeId);

    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM PaiementClient p WHERE p.client.id = :clientId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActifsByClientId(@Param("clientId") Long clientId);

    // Facture d'avant la refonte (legacy) : argent reçu APRÈS la reprise sur cette
    // facture (payer), qui s'ajoute à son montant payé historique. Les paiements créés
    // par la reprise elle-même (observations « Reprise... », voir
    // RepriseCircuitClientService.PREFIXE_OBSERVATIONS) sont exclus : ils reprennent des
    // paiements déjà comptés dans ce montant payé historique.
    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM PaiementClient p WHERE p.facture.id = :factureId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND (p.observations IS NULL OR p.observations NOT LIKE 'Reprise%')")
    Double sumActifsHorsRepriseByFactureId(@Param("factureId") Long factureId);

    // Comptabilité : encaissé sur une période (bornes toujours concrètes).
    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM PaiementClient p WHERE p.farm.id = :farmId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND p.date >= :dateDebut AND p.date <= :dateFin")
    Double sumActifsByFarmAndDates(@Param("farmId") Long farmId,
                                   @Param("dateDebut") java.time.LocalDate dateDebut,
                                   @Param("dateFin") java.time.LocalDate dateFin);

    // Argent réservé aux commandes ouvertes du client (voir CompteClientService.estReservee) :
    // [commandeUniqueId, dateCommande, Σ paiements actifs rattachés]. Même filtre que
    // ImputationPaiementRepo.sumImputeParCommandeOuverte, à garder identiques.
    @Query("SELECT k.uniqueId, k.dateCommande, SUM(p.montant) FROM PaiementClient p JOIN p.commande k " +
           "WHERE p.client.id = :clientId AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND k.statut IN (com.diafarms.ml.models.Commande.StatutCommande.EN_ATTENTE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.CONFIRMEE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.EN_LIVRAISON) " +
           "AND COALESCE(k.initialisation.removed, false) = false GROUP BY k.uniqueId, k.dateCommande ORDER BY k.dateCommande, k.uniqueId")
    List<Object[]> sumParCommandeOuverte(@Param("clientId") Long clientId);

    // CommandeServiceImpl.enrichirTous : paiements de toutes les commandes d'une page.
    @Query("SELECT p FROM PaiementClient p WHERE p.commande.id IN :ids")
    List<PaiementClient> findByCommandeIds(@Param("ids") java.util.Collection<Long> ids);

    // Versions groupées par client de sumActifsByClientId / sumParCommandeOuverte
    // (GET /clients/comptes : un seul appel pour toute une page de clients).
    @Query("SELECT p.client.id, COALESCE(SUM(p.montant), 0) FROM PaiementClient p WHERE p.client.id IN :clientIds " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF GROUP BY p.client.id")
    List<Object[]> sumActifsParClient(@Param("clientIds") java.util.Collection<Long> clientIds);

    @Query("SELECT p.client.id, k.uniqueId, k.dateCommande, SUM(p.montant) FROM PaiementClient p JOIN p.commande k " +
           "WHERE p.client.id IN :clientIds AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND k.statut IN (com.diafarms.ml.models.Commande.StatutCommande.EN_ATTENTE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.CONFIRMEE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.EN_LIVRAISON) " +
           "AND COALESCE(k.initialisation.removed, false) = false " +
           "GROUP BY p.client.id, k.uniqueId, k.dateCommande ORDER BY k.dateCommande, k.uniqueId")
    List<Object[]> sumParCommandeOuverteParClient(@Param("clientIds") java.util.Collection<Long> clientIds);
}
