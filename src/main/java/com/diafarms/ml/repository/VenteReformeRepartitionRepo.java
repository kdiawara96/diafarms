package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.VenteReformeRepartition;

@Repository
public interface VenteReformeRepartitionRepo extends JpaRepository<VenteReformeRepartition, Long> {

    Optional<VenteReformeRepartition> findByUniqueId(String uniqueId);

    List<VenteReformeRepartition> findByVenteReforme_UniqueId(String venteReformeUniqueId);

    // Déjà vendu POUR CE PROJET — voir VenteOeufsRepartitionRepo.sumQuantiteByProjetId.
    @Query("SELECT COALESCE(SUM(r.nombreSujetsAttribue), 0) FROM VenteReformeRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteReforme.initialisation.removed = false")
    Integer sumSujetsByProjetId(@Param("projetId") Long projetId);
}
