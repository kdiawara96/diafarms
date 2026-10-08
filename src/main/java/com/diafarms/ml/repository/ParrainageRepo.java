package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Parrainage;

@Repository
public interface ParrainageRepo extends JpaRepository<Parrainage, Long> {

    @Query("SELECT p FROM Parrainage p JOIN FETCH p.parrain JOIN FETCH p.filleul WHERE p.filleul.id = :farmId")
    Optional<Parrainage> findByFilleulId(@Param("farmId") Long farmId);

    @Query("SELECT p FROM Parrainage p JOIN FETCH p.parrain JOIN FETCH p.filleul WHERE p.parrain.id = :farmId ORDER BY p.creeLe DESC")
    List<Parrainage> findByParrainId(@Param("farmId") Long farmId);

    // Récompense réservée une seule fois : 1 si cette exécution l'obtient, 0 sinon.
    @Modifying
    @Query(value = "UPDATE parrainages SET recompense_le = now() WHERE id = :id AND recompense_le IS NULL", nativeQuery = true)
    int reserverRecompense(@Param("id") Long id);
}
