package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.diafarms.ml.models.Vaccination;

public interface VaccinationRepo extends JpaRepository<Vaccination, Long> {
    Optional<Vaccination> findByUniqueId(String uniqueId);

    @Query("SELECT v FROM Vaccination v WHERE v.projet.uniqueId = :uniqueId AND v.initialisation.removed = false")
    List<Vaccination> findByProjetUniqueIdAndInitialisationRemovedFalse(@Param("uniqueId") String uniqueId);
}
