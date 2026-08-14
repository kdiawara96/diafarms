package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Personnel;

@Repository
public interface PersonnelRepo extends JpaRepository<Personnel, Long> {

    Personnel findByUniqueId(String uniqueId);

    @Query("SELECT p FROM Personnel p WHERE p.farm.id = :farmId AND p.initialisation.removed = false ORDER BY p.nom")
    List<Personnel> findAllActiveByFarmId(@Param("farmId") Long farmId);
}
