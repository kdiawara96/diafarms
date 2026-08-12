package com.diafarms.ml.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Commande;

@Repository
public interface CommandeRepo extends JpaRepository<Commande, Long> {

    Commande findByUniqueId(String uniqueId);

    // Pas d'ORDER BY ici : le tri vient du Pageable (Sort.by("dateCommande") côté
    // service) — un ORDER BY explicite en plus provoquerait un conflit.
    @Query("SELECT c FROM Commande c WHERE c.farm.id = :farmId AND c.initialisation.removed = false " +
        "AND (:statut IS NULL OR c.statut = :statut) " +
        "AND (:clientUniqueId IS NULL OR c.client.uniqueId = :clientUniqueId)")
    Page<Commande> search(@Param("farmId") Long farmId,
                           @Param("statut") Commande.StatutCommande statut,
                           @Param("clientUniqueId") String clientUniqueId,
                           Pageable pageable);
}
