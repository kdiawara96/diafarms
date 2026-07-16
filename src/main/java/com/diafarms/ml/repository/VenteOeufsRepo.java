package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.VenteOeufs;

@Repository
public interface VenteOeufsRepo extends JpaRepository<VenteOeufs, Long> {

    Optional<VenteOeufs> findByUniqueId(String uniqueId);

    @Query("SELECT v FROM VenteOeufs v WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Page<VenteOeufs> search(@Param("farmId") Long farmId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(v.quantiteOeufs), 0) FROM VenteOeufs v " +
        "WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Integer sumQuantiteByFarmId(@Param("farmId") Long farmId);
}
