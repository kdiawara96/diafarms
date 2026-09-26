package com.diafarms.ml.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.diafarms.ml.enums.CibleImputation;
import com.diafarms.ml.models.ImputationPaiement;

public interface ImputationPaiementRepo extends JpaRepository<ImputationPaiement, Long> {
    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.paiement.id = :paiementId " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesByPaiementId(@Param("paiementId") Long paiementId);

    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.cibleType = :type " +
           "AND i.cibleUniqueId = :uid AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesByCible(@Param("type") CibleImputation type, @Param("uid") String uid);

    // Plus récentes d'abord : c'est dans cet ordre qu'on les annule quand une vente baisse.
    @Query("SELECT i FROM ImputationPaiement i JOIN FETCH i.paiement WHERE i.cibleType = :type " +
           "AND i.cibleUniqueId = :uid AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF ORDER BY i.id DESC")
    List<ImputationPaiement> findActivesByCible(@Param("type") CibleImputation type, @Param("uid") String uid);

    @Query("SELECT i FROM ImputationPaiement i WHERE i.paiement.id = :paiementId " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    List<ImputationPaiement> findActivesByPaiementId(@Param("paiementId") Long paiementId);

    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.client.id = :clientId " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesByClientId(@Param("clientId") Long clientId);

    @Query("SELECT COALESCE(SUM(i.montant), 0) FROM ImputationPaiement i WHERE i.client.id = :clientId " +
           "AND i.cibleType <> com.diafarms.ml.enums.CibleImputation.REMBOURSEMENT " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF")
    Double sumActivesSurVentesByClientId(@Param("clientId") Long clientId);

    // Historique d'une vente ou d'un paiement (annulées comprises).
    @Query("SELECT i FROM ImputationPaiement i JOIN FETCH i.paiement WHERE i.client.id = :clientId ORDER BY i.id DESC")
    List<ImputationPaiement> findAllByClientId(@Param("clientId") Long clientId);

    // [commandeUniqueId, Σ imputations actives des paiements actifs rattachés à cette
    // commande ouverte]. Voir PaiementClientRepo.sumParCommandeOuverte.
    @Query("SELECT k.uniqueId, SUM(i.montant) FROM ImputationPaiement i JOIN i.paiement p JOIN p.commande k " +
           "WHERE i.client.id = :clientId AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND k.statut IN (com.diafarms.ml.models.Commande.StatutCommande.EN_ATTENTE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.CONFIRMEE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.EN_LIVRAISON) " +
           "AND COALESCE(k.initialisation.removed, false) = false GROUP BY k.uniqueId")
    List<Object[]> sumImputeParCommandeOuverte(@Param("clientId") Long clientId);

    // Reprise « acompte réservé » : imputations actives d'un paiement rattaché à une
    // commande, avec la commande chargée (le contrôle « vente de la commande ? » se fait
    // dans RepriseAcompteReserveService).
    @Query("SELECT i FROM ImputationPaiement i JOIN FETCH i.paiement p JOIN FETCH p.commande k " +
           "WHERE i.client.id = :clientId AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF ORDER BY i.id")
    List<ImputationPaiement> findActivesDePaiementsDeCommandeByClientId(@Param("clientId") Long clientId);

    // Imputations actives des paiements actifs rattachés à une commande (re-réservation,
    // voir CompteClientService.annulerHorsCommande).
    @Query("SELECT i FROM ImputationPaiement i JOIN FETCH i.paiement p WHERE p.commande.id = :commandeId " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF ORDER BY i.id")
    List<ImputationPaiement> findActivesDePaiementsDeCommande(@Param("commandeId") Long commandeId);

    // CommandeServiceImpl.enrichirTous : [cibleUniqueId, Σ imputations actives] pour une
    // liste de ventes.
    @Query("SELECT i.cibleUniqueId, SUM(i.montant) FROM ImputationPaiement i WHERE i.cibleUniqueId IN :uids " +
           "AND i.cibleType <> com.diafarms.ml.enums.CibleImputation.REMBOURSEMENT " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF GROUP BY i.cibleUniqueId")
    List<Object[]> sumActivesParVente(@Param("uids") java.util.Collection<String> uids);

    // CommandeServiceImpl.enrichirTous : [paiementId, cibleType, cibleUniqueId, Σ] pour
    // une liste de paiements.
    @Query("SELECT i.paiement.id, i.cibleType, i.cibleUniqueId, SUM(i.montant) FROM ImputationPaiement i " +
           "WHERE i.paiement.id IN :ids AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "GROUP BY i.paiement.id, i.cibleType, i.cibleUniqueId")
    List<Object[]> sumActivesParPaiementEtCible(@Param("ids") java.util.Collection<Long> ids);

    // Versions groupées par client (GET /clients/comptes) de sumActivesByClientId,
    // sumActivesSurVentesByClientId et sumImputeParCommandeOuverte.
    @Query("SELECT i.client.id, COALESCE(SUM(i.montant), 0), " +
           "COALESCE(SUM(CASE WHEN i.cibleType <> com.diafarms.ml.enums.CibleImputation.REMBOURSEMENT THEN i.montant ELSE 0 END), 0) " +
           "FROM ImputationPaiement i WHERE i.client.id IN :clientIds " +
           "AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF GROUP BY i.client.id")
    List<Object[]> sumActivesParClient(@Param("clientIds") java.util.Collection<Long> clientIds);

    @Query("SELECT k.uniqueId, SUM(i.montant) FROM ImputationPaiement i JOIN i.paiement p JOIN p.commande k " +
           "WHERE i.client.id IN :clientIds AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND k.statut IN (com.diafarms.ml.models.Commande.StatutCommande.EN_ATTENTE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.CONFIRMEE, " +
           "com.diafarms.ml.models.Commande.StatutCommande.EN_LIVRAISON) " +
           "AND COALESCE(k.initialisation.removed, false) = false GROUP BY k.uniqueId")
    List<Object[]> sumImputeParCommandeOuverteParClients(@Param("clientIds") java.util.Collection<Long> clientIds);
}
