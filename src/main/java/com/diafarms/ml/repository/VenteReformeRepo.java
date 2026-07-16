package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.VenteReforme;

@Repository
public interface VenteReformeRepo extends JpaRepository<VenteReforme, Long> {

    Optional<VenteReforme> findByUniqueId(String uniqueId);

    @Query("SELECT v FROM VenteReforme v WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Page<VenteReforme> search(@Param("farmId") Long farmId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(v.nombreSujets), 0) FROM VenteReforme v " +
        "WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Integer sumSujetsVendusByFarmId(@Param("farmId") Long farmId);
}
