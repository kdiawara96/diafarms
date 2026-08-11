package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.SoldeClient;

@Repository
public interface SoldeClientRepo extends JpaRepository<SoldeClient, Long> {

    Optional<SoldeClient> findByClient_Id(Long clientId);

    @Query("SELECT s FROM SoldeClient s WHERE s.client.uniqueId = :clientUniqueId AND s.farm.id = :farmId")
    Optional<SoldeClient> findByClientUniqueIdAndFarmId(@Param("clientUniqueId") String clientUniqueId, @Param("farmId") Long farmId);

    // Vue d'ensemble admin/responsable/comptable/vente : tous les soldes client non
    // nuls de la ferme, pour repérer d'un coup d'œil qui doit de l'argent.
    @Query("SELECT s FROM SoldeClient s WHERE s.farm.id = :farmId AND s.solde <> 0.0 ORDER BY s.solde DESC")
    List<SoldeClient> findAllNonZeroByFarmId(@Param("farmId") Long farmId);

    // Somme des dettes client en cours (soldes positifs uniquement) — même principe
    // que SoldeVendeurRepo.sumSoldePositifByFarmId, pour un futur KPI "Total dû par
    // les clients" distinct du "Total dû par les vendeurs".
    @Query("SELECT COALESCE(SUM(s.solde), 0) FROM SoldeClient s WHERE s.farm.id = :farmId AND s.solde > 0.0")
    Double sumSoldePositifByFarmId(@Param("farmId") Long farmId);
}
