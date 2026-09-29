package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.diafarms.ml.models.AffectationPersonnel;

public interface AffectationPersonnelRepo extends JpaRepository<AffectationPersonnel, Long> {

    Optional<AffectationPersonnel> findByUniqueId(String uniqueId);

    @Query("SELECT a FROM AffectationPersonnel a JOIN FETCH a.projet WHERE a.personnel.id = :personnelId "
            + "AND a.initialisation.removed = false ORDER BY a.dateDebut DESC")
    List<AffectationPersonnel> findActivesByPersonnelId(@Param("personnelId") Long personnelId);

    @Query("SELECT a FROM AffectationPersonnel a JOIN FETCH a.projet JOIN FETCH a.personnel WHERE a.farm.id = :farmId "
            + "AND a.initialisation.removed = false")
    List<AffectationPersonnel> findActivesByFarmId(@Param("farmId") Long farmId);
}
