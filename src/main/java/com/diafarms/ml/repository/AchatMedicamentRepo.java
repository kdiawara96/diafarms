package com.diafarms.ml.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.diafarms.ml.models.AchatMedicament;

public interface AchatMedicamentRepo extends JpaRepository<AchatMedicament, Long> {

    Optional<AchatMedicament> findByUniqueId(String uniqueId);

    @Query("SELECT a FROM AchatMedicament a JOIN FETCH a.projet LEFT JOIN FETCH a.batiment WHERE a.projet.id = :projetId "
            + "AND a.initialisation.removed = false ORDER BY a.dateAchat DESC, a.id DESC")
    List<AchatMedicament> findActifsByProjetId(@Param("projetId") Long projetId);

    @Query("SELECT a FROM AchatMedicament a JOIN FETCH a.projet LEFT JOIN FETCH a.batiment WHERE a.farm.id = :farmId "
            + "AND a.initialisation.removed = false AND a.dateAchat >= :depuis ORDER BY a.dateAchat DESC, a.id DESC")
    List<AchatMedicament> findRecentsByFarmId(@Param("farmId") Long farmId, @Param("depuis") LocalDate depuis);
}
