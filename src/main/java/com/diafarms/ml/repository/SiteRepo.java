package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Site;

@Repository
public interface SiteRepo extends JpaRepository<Site, Long> {

    Optional<Site> findByUniqueId(String uniqueId);

    @Query("SELECT s FROM Site s WHERE s.farm.id = :farmId AND s.initialisation.removed = false ORDER BY s.nom")
    List<Site> findAllActiveByFarm(@Param("farmId") Long farmId);

    boolean existsByNomIgnoreCaseAndFarmIdAndInitialisationRemovedFalse(String nom, Long farmId);
}
