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

    // Livraison / clôture : deux actions simultanées sur la même commande sont sérialisées.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT c FROM Commande c WHERE c.uniqueId = :uid")
    Commande findByUniqueIdForUpdate(@Param("uid") String uniqueId);

    // Pas d'ORDER BY ici : le tri vient du Pageable (Sort.by("dateCommande") côté
    // service) — un ORDER BY explicite en plus provoquerait un conflit.
    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel :
    // "(:x IS NULL OR ...)" fait planter Postgres ("could not determine data type of
    // parameter", voir TransactionRepo.search / FactureRepo.search). statut et
    // clientUniqueId reçoivent une valeur factice non nulle quand hasX = false.
    @Query("SELECT c FROM Commande c WHERE c.farm.id = :farmId AND c.initialisation.removed = false " +
        "AND (:hasStatut = false OR c.statut = :statut) " +
        "AND (:hasClient = false OR c.client.uniqueId = :clientUniqueId)")
    Page<Commande> search(@Param("farmId") Long farmId,
                           @Param("hasStatut") boolean hasStatut, @Param("statut") Commande.StatutCommande statut,
                           @Param("hasClient") boolean hasClient, @Param("clientUniqueId") String clientUniqueId,
                           Pageable pageable);
}
