package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Batiment.StatutBatiment;


@Repository
public interface BatimentRepo extends JpaRepository<Batiment, Long> {
	Batiment findByUniqueId(String uniqueId);
    Optional<Batiment> findOptionalByNom(String nom);

    @Query("""
        SELECT COUNT(b) > 0
        FROM Batiment b
        WHERE LOWER(b.nom) = LOWER(:nom)
        AND b.farm.id = :farmId
        AND b.initialisation.removed = false
    """)
    boolean existsByNomIgnoreCaseAndFarmId(
            @Param("nom") String nom,
            @Param("farmId") Long farmId);

    // Récupère uniquement les bâtiments non supprimés
    @Query("SELECT b FROM Batiment b WHERE b.initialisation.removed = false")
    List<Batiment> findAllActive();


    @Query("SELECT b FROM Batiment b WHERE b.farm.id = :farmId AND b.initialisation.removed = false")
    List<Batiment> findActiveByFarmId(@Param("farmId") Long farmId);

    @Query("""
        SELECT b
        FROM Batiment b
        WHERE b.farm.id = :farmId
        AND b.initialisation.removed = false
        AND NOT EXISTS (
            SELECT o
            FROM OccupationBatiment o
            WHERE o.batiment = b
            AND (o.dateSortie IS NULL OR o.dateSortie >= CURRENT_DATE)
        )
        """)
    List<Batiment> findAvailableByFarmId(@Param("farmId") Long farmId);

    // Recherche par nom, localisation, type, etc.
    @Query("SELECT b FROM Batiment b WHERE b.initialisation.removed = false " +
           "AND (LOWER(b.nom) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(b.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<Batiment> searchBatiments(@Param("search") String search);
    

    @Query("SELECT b FROM Batiment b WHERE b.farm.id = :farmId " +
           "AND b.initialisation.removed = false " +
           "AND (LOWER(b.nom) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(b.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<Batiment> searchBatimentsByFarm(
        @Param("farmId") Long farmId, 
        @Param("search") String search
    );

    // Optionnel : compter par statut
    long countByStatut(StatutBatiment statut);
}
