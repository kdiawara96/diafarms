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

    // Comptabilité : encaissé sur une période (bornes toujours concrètes).
    @Query("SELECT COALESCE(SUM(p.montant), 0) FROM PaiementClient p WHERE p.farm.id = :farmId " +
           "AND p.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND p.date >= :dateDebut AND p.date <= :dateFin")
    Double sumActifsByFarmAndDates(@Param("farmId") Long farmId,
                                   @Param("dateDebut") java.time.LocalDate dateDebut,
                                   @Param("dateFin") java.time.LocalDate dateFin);
}
