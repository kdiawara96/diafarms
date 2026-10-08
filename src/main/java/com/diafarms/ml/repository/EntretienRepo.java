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
    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel, valeur
    // factice non nulle quand hasX = false : jamais de "(:x IS NULL OR ...)" (plantage
    // Postgres dès que le type du paramètre nul est inconnu, voir CommandeRepo.search).
    @Query("SELECT e FROM Entretien e LEFT JOIN e.batiment b WHERE e.farm.id = :farmId " +
        "AND e.initialisation.removed = false " +
        "AND (:hasBatiment = false OR b.uniqueId = :batimentUniqueId) " +
        "AND (:hasNiveau = false OR e.niveau = :niveau) " +
        "AND (:hasType = false OR e.type = :type) " +
        "AND (:hasSearch = false OR LOWER(e.description) LIKE :search OR LOWER(e.observations) LIKE :search)")
    Page<Entretien> search(@Param("farmId") Long farmId,
                            @Param("hasBatiment") boolean hasBatiment, @Param("batimentUniqueId") String batimentUniqueId,
                            @Param("hasNiveau") boolean hasNiveau, @Param("niveau") NiveauEntretien niveau,
                            @Param("hasType") boolean hasType, @Param("type") TypeEntretien type,
                            @Param("hasSearch") boolean hasSearch, @Param("search") String search,
                            Pageable pageable);
}
