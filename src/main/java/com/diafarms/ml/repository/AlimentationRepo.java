package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

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
}
