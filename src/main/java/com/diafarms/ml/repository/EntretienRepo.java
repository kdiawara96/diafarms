package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.NiveauEntretien;
import com.diafarms.ml.enums.TypeEntretien;
import com.diafarms.ml.models.Entretien;

@Repository
public interface EntretienRepo extends JpaRepository<Entretien, Long> {

    Optional<Entretien> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur batiment : un chemin implicite dans le WHERE forcerait
    // un INNER JOIN et ferait disparaître les entrées SITE (batiment_id null) — même
    // piège que SoinsRepo.search.
    @Query("SELECT e FROM Entretien e LEFT JOIN e.batiment b WHERE e.farm.id = :farmId " +
        "AND e.initialisation.removed = false " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId) " +
        "AND (:niveau IS NULL OR e.niveau = :niveau) " +
        "AND (:type IS NULL OR e.type = :type) " +
        "AND (:search IS NULL OR LOWER(e.description) LIKE :search OR LOWER(e.observations) LIKE :search)")
    Page<Entretien> search(@Param("farmId") Long farmId,
                            @Param("batimentUniqueId") String batimentUniqueId,
                            @Param("niveau") NiveauEntretien niveau,
                            @Param("type") TypeEntretien type,
                            @Param("search") String search,
                            Pageable pageable);
}
