package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Client;

@Repository
public interface ClientRepo extends JpaRepository<Client, Long> {

    Client findByUniqueId(String uniqueId);

    @Query("SELECT COUNT(c) > 0 FROM Client c WHERE LOWER(c.nom) = LOWER(:nom) AND c.farm.id = :farmId AND c.initialisation.removed = false")
    boolean existsByNomIgnoreCaseAndFarmId(@Param("nom") String nom, @Param("farmId") Long farmId);

    @Query("SELECT c FROM Client c WHERE c.farm.id = :farmId AND c.initialisation.removed = false ORDER BY c.nom")
    List<Client> findAllActiveByFarmId(@Param("farmId") Long farmId);

    @Query("SELECT c FROM Client c WHERE c.farm.id = :farmId AND c.initialisation.removed = false " +
        "AND (LOWER(c.nom) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(COALESCE(c.telephone, '')) LIKE LOWER(CONCAT('%', :search, '%')))")
    Page<Client> searchByFarm(@Param("farmId") Long farmId, @Param("search") String search, Pageable pageable);

    @Query("SELECT c FROM Client c WHERE c.farm.id = :farmId AND c.initialisation.removed = false")
    Page<Client> findActiveByFarmId(@Param("farmId") Long farmId, Pageable pageable);
}
