package com.diafarms.ml.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Salaire;

@Repository
public interface SalaireRepo extends JpaRepository<Salaire, Long> {

    Salaire findByUniqueId(String uniqueId);

    Salaire findByEmploye_UniqueIdAndFarm_Id(String employeUniqueId, Long farmId);

    // Pas d'ORDER BY : le tri vient du Pageable, comme CommandeRepo/FactureRepo.search.
    @Query("SELECT s FROM Salaire s WHERE s.farm.id = :farmId AND s.initialisation.removed = false")
    Page<Salaire> search(@Param("farmId") Long farmId, Pageable pageable);
}
