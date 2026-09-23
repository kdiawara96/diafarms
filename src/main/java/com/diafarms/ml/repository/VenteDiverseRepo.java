package com.diafarms.ml.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.VenteDiverse;

@Repository
public interface VenteDiverseRepo extends JpaRepository<VenteDiverse, Long> {

    Optional<VenteDiverse> findByUniqueId(String uniqueId);

    // Bornes de dates attendues NON NULLES (voir TransactionServiceImpl.deb/fin) :
    // jamais de ":param IS NULL OR" avec Postgres.
    @Query("SELECT v FROM VenteDiverse v LEFT JOIN FETCH v.creePar LEFT JOIN FETCH v.demandeSuppressionPar " +
        "WHERE v.farm.id = :farmId AND v.initialisation.removed = false " +
        "AND v.date >= :dateDebut AND v.date <= :dateFin")
    List<VenteDiverse> findActives(@Param("farmId") Long farmId,
                                   @Param("dateDebut") LocalDate dateDebut,
                                   @Param("dateFin") LocalDate dateFin);

    @Query("SELECT v FROM VenteDiverse v LEFT JOIN FETCH v.demandeSuppressionPar WHERE v.uniqueId IN :uniqueIds")
    List<VenteDiverse> findByUniqueIds(@Param("uniqueIds") List<String> uniqueIds);
}
