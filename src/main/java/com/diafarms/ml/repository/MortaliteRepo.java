package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Mortalite;

@Repository
public interface MortaliteRepo extends JpaRepository<Mortalite, Long> {

    Optional<Mortalite> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    @Query("SELECT m FROM Mortalite m LEFT JOIN m.projet p LEFT JOIN m.batiment b WHERE m.farm.id = :farmId " +
        "AND m.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId) " +
        "AND (:search IS NULL OR LOWER(m.cause) LIKE :search)")
    Page<Mortalite> search(@Param("farmId") Long farmId,
                            @Param("projetUniqueId") String projetUniqueId,
                            @Param("batimentUniqueId") String batimentUniqueId,
                            @Param("search") String search,
                            Pageable pageable);

    @Query("SELECT COALESCE(SUM(m.nombreMorts), 0) FROM Mortalite m " +
        "WHERE m.projet.id = :projetId AND m.initialisation.removed = false")
    Integer sumMortsByProjetId(@Param("projetId") Long projetId);
}
