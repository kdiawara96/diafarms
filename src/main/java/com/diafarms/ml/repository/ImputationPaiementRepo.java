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
}
