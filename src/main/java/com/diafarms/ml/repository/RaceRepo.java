package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Race;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

@Repository
public interface RaceRepo extends JpaRepository<Race, Long> {
	Race findByUniqueId(String uniqueId);

	Optional<Race> findOptionalByNom(String nom);

	@Query("SELECT r FROM Race r WHERE r.initialisation.removed = false")
	List<Race> findAllActive();

    @Query("SELECT r FROM Race r WHERE r.farm.id = :farmId AND r.initialisation.removed = false")
    List<Race> findAllActiveByFarm(@Param("farmId") Long farmId);

	@Query("SELECT r FROM Race r WHERE r.initialisation.removed = false AND (LOWER(r.nom) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(r.origine) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(r.description) LIKE LOWER(CONCAT('%', :search, '%')))")
	List<Race> searchRaces(@Param("search") String search);

    // 2. Rechercher des races par mot-clé au sein d'une ferme spécifique
    @Query("SELECT r FROM Race r WHERE r.farm.id = :farmId " +
           "AND r.initialisation.removed = false " +
           "AND (LOWER(r.nom) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(r.origine) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "OR LOWER(r.description) LIKE LOWER(CONCAT('%', :search, '%')))")
    List<Race> searchRacesByFarm(@Param("farmId") Long farmId, @Param("search") String search);

    @Query("SELECT COUNT(r) > 0 FROM Race r WHERE r.identifiant = :identifiant")
    boolean existsByIdentifiant(@Param("identifiant") String identifiant);
}
