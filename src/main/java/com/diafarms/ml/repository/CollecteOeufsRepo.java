package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.CollecteOeufs;

@Repository
public interface CollecteOeufsRepo extends JpaRepository<CollecteOeufs, Long> {

    Optional<CollecteOeufs> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    @Query("SELECT c FROM CollecteOeufs c LEFT JOIN c.projet p LEFT JOIN c.batiment b WHERE c.farm.id = :farmId " +
        "AND c.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId)")
    Page<CollecteOeufs> search(@Param("farmId") Long farmId,
                                @Param("projetUniqueId") String projetUniqueId,
                                @Param("batimentUniqueId") String batimentUniqueId,
                                Pageable pageable);
}
