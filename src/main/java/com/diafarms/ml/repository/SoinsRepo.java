package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Soins;

@Repository
public interface SoinsRepo extends JpaRepository<Soins, Long> {

    Optional<Soins> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    @Query("SELECT s FROM Soins s LEFT JOIN s.projet p LEFT JOIN s.batiment b WHERE s.farm.id = :farmId " +
        "AND s.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId) " +
        "AND (:search IS NULL OR LOWER(s.produit) LIKE :search OR LOWER(s.observations) LIKE :search OR LOWER(s.type) LIKE :search)")
    Page<Soins> search(@Param("farmId") Long farmId,
                        @Param("projetUniqueId") String projetUniqueId,
                        @Param("batimentUniqueId") String batimentUniqueId,
                        @Param("search") String search,
                        Pageable pageable);
}
