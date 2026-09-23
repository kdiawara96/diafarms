package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.diafarms.ml.models.RemboursementClient;

public interface RemboursementClientRepo extends JpaRepository<RemboursementClient, Long> {
    Optional<RemboursementClient> findByUniqueId(String uniqueId);

    @Query("SELECT r FROM RemboursementClient r WHERE r.client.id = :clientId ORDER BY r.date DESC, r.id DESC")
    List<RemboursementClient> findAllByClientId(@Param("clientId") Long clientId);

    @Query("SELECT COALESCE(SUM(r.montant), 0) FROM RemboursementClient r WHERE r.farm.id = :farmId " +
           "AND r.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND r.date >= :dateDebut AND r.date <= :dateFin")
    Double sumActifsByFarmAndDates(@Param("farmId") Long farmId,
                                   @Param("dateDebut") java.time.LocalDate dateDebut,
                                   @Param("dateFin") java.time.LocalDate dateFin);
}
