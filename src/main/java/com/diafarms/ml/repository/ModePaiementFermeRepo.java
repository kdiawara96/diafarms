package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.ModePaiementFerme;

@Repository
public interface ModePaiementFermeRepo extends JpaRepository<ModePaiementFerme, Long> {

    List<ModePaiementFerme> findByFarm_IdOrderByOrdreAscIdAsc(Long farmId);

    @Modifying
    @Query("delete from ModePaiementFerme m where m.farm.id = :farmId")
    void supprimerPourFerme(@Param("farmId") Long farmId);
}
