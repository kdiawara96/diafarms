package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Projets;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface ProjetsRepo extends JpaRepository<Projets, Long> {
    
   Optional<Projets> findByUniqueId(String uniqueId);

   @Query("SELECT p FROM Projets p WHERE p.farm.id = :farmId " +
        "AND p.initialisation.removed = false " +
        "AND (:isArchive IS NULL OR p.initialisation.archive = :isArchive) " +
        "AND (:search IS NULL OR LOWER(p.titre) LIKE :search " +
        "OR LOWER(p.uniqueId) LIKE :search " +
        "OR LOWER(p.code) LIKE :search " +
        "OR LOWER(p.responsable) LIKE :search " +
        "OR LOWER(p.fournisseurs_poussins) LIKE :search)")
    Page<Projets> searchProjets(@Param("farmId") Long farmId, 
                                @Param("isArchive") Boolean isArchive, 
                                @Param("search") String search, 
                                Pageable pageable);

    @Query("SELECT COUNT(p) > 0 FROM Projets p WHERE p.code = :code")
    boolean existsByCode(@Param("code") String code);
}
