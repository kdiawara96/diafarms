package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Alimentation;

@Repository
public interface AlimentationRepo extends JpaRepository<Alimentation, Long> {

    Optional<Alimentation> findByUniqueId(String uniqueId);
    @Query("SELECT a FROM Alimentation a WHERE a.projet.uniqueId = :uniqueId AND a.initialisation.removed = false")
    List<Alimentation> findByProjetUniqueIdAndInitialisationRemovedFalse(@Param("uniqueId") String uniqueId);
    @Query("SELECT a FROM Alimentation a WHERE a.uniqueId = :uniqueId AND a.initialisation.removed = false")
    Optional<Alimentation> findByUniqueIdAndInitialisationRemovedFalse(@Param("uniqueId") String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    @Query("SELECT a FROM Alimentation a LEFT JOIN a.projet p LEFT JOIN a.batiment b WHERE a.farm.id = :farmId " +
        "AND a.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId) " +
        "AND (:search IS NULL OR LOWER(a.nomAliment) LIKE :search OR LOWER(a.observations) LIKE :search)")
    Page<Alimentation> search(@Param("farmId") Long farmId,
                               @Param("projetUniqueId") String projetUniqueId,
                               @Param("batimentUniqueId") String batimentUniqueId,
                               @Param("search") String search,
                               Pageable pageable);

    @Query("SELECT COALESCE(SUM(a.quantiteKg), 0.0) FROM Alimentation a " +
        "WHERE a.projet.id = :projetId AND a.initialisation.removed = false")
    Double sumAcheteByProjetId(@Param("projetId") Long projetId);
}
