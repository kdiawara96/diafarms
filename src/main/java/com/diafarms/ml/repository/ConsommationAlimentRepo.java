package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.ConsommationAliment;

@Repository
public interface ConsommationAlimentRepo extends JpaRepository<ConsommationAliment, Long> {

    Optional<ConsommationAliment> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    @Query("SELECT c FROM ConsommationAliment c LEFT JOIN c.projet p LEFT JOIN c.batiment b WHERE c.farm.id = :farmId " +
        "AND c.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId)")
    Page<ConsommationAliment> search(@Param("farmId") Long farmId,
                                      @Param("projetUniqueId") String projetUniqueId,
                                      @Param("batimentUniqueId") String batimentUniqueId,
                                      Pageable pageable);

    @Query("SELECT COALESCE(SUM(c.quantiteKg), 0.0) FROM ConsommationAliment c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false")
    Double sumConsommeByProjetId(@Param("projetId") Long projetId);
}
