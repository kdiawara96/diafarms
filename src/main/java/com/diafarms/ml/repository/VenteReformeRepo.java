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

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    @Query("SELECT v FROM VenteReforme v LEFT JOIN v.projet p LEFT JOIN v.batiment b WHERE v.farm.id = :farmId " +
        "AND v.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId)")
    Page<VenteReforme> search(@Param("farmId") Long farmId,
                               @Param("projetUniqueId") String projetUniqueId,
                               @Param("batimentUniqueId") String batimentUniqueId,
                               Pageable pageable);

    @Query("SELECT COALESCE(SUM(v.nombreSujets), 0) FROM VenteReforme v " +
        "WHERE v.projet.id = :projetId AND v.initialisation.removed = false")
    Integer sumSujetsVendusByProjetId(@Param("projetId") Long projetId);
}
