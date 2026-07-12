package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.diafarms.ml.models.OccupationBatiment;

public interface OccupationBatimentRepo extends JpaRepository<OccupationBatiment, Long> {

    // Disponibilité calculée en direct à partir des dates d'occupation, plutôt
    // que via Batiment.statut (un simple flag mis à jour manuellement à chaque
    // assignation/libération, jamais recalculé automatiquement quand une
    // dateSortie passe) — même logique que BatimentRepo.findAvailableByFarmId,
    // pour que la liste de sélection et la validation à la création soient
    // toujours d'accord sur ce qui est "occupé".
    @Query("""
        SELECT COUNT(o) > 0
        FROM OccupationBatiment o
        WHERE o.batiment.id = :batimentId
        AND (o.dateSortie IS NULL OR o.dateSortie > CURRENT_DATE)
        """)
    boolean existsOccupationActive(@Param("batimentId") Long batimentId);
}
