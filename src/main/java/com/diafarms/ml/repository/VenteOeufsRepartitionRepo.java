package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.VenteOeufsRepartition;

@Repository
public interface VenteOeufsRepartitionRepo extends JpaRepository<VenteOeufsRepartition, Long> {

    Optional<VenteOeufsRepartition> findByUniqueId(String uniqueId);

    List<VenteOeufsRepartition> findByVenteOeufs_UniqueId(String venteOeufsUniqueId);

    // Déjà vendu POUR CE PROJET (toutes ventes farm-wide confondues) — sert à
    // calculer le stock d'œufs restant du projet, part qu'il peut encore
    // contribuer à une prochaine vente (voir VenteOeufsImpl.stockDisponibleParProjet).
    @Query("SELECT COALESCE(SUM(r.quantiteAttribuee), 0) FROM VenteOeufsRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteOeufs.initialisation.removed = false")
    Integer sumQuantiteByProjetId(@Param("projetId") Long projetId);
}
