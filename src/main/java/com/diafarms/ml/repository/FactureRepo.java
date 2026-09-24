package com.diafarms.ml.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Facture;

@Repository
public interface FactureRepo extends JpaRepository<Facture, Long> {

    Facture findByUniqueId(String uniqueId);

    boolean existsByNumeroFacture(String numeroFacture);

    long countByFarm_Id(Long farmId);

    // Une seule facture par vente/commande d'origine — voir FactureServiceImpl.genererDepuis.
    boolean existsBySourceTypeAndSourceUniqueId(Facture.SourceFacture sourceType, String sourceUniqueId);

    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel —
    // comparer un paramètre directement à NULL ("(:x IS NULL OR ...)") fait planter
    // Postgres ("could not determine data type of parameter", SQLState 42P18), voir
    // TransactionRepo.search pour le même motif. statut/clientUniqueId reçoivent une
    // valeur factice non-nulle quand hasX=false (jamais évaluée grâce au court-circuit).
    // Pas d'ORDER BY ici : le tri vient du Pageable, comme CommandeRepo.search.
    @Query("SELECT f FROM Facture f WHERE f.farm.id = :farmId AND f.initialisation.removed = false " +
        "AND (:hasStatut = false OR f.statut = :statut) " +
        "AND (:hasClient = false OR f.client.uniqueId = :clientUniqueId)")
    Page<Facture> search(@Param("farmId") Long farmId,
                          @Param("hasStatut") boolean hasStatut, @Param("statut") Facture.StatutFacture statut,
                          @Param("hasClient") boolean hasClient, @Param("clientUniqueId") String clientUniqueId,
                          Pageable pageable);

    // Même filtre que search(), sans pagination base — utilisé par
    // FactureServiceImpl.list quand le statut demandé (PAYEE/PARTIELLE/IMPAYEE) est
    // calculé à la volée (pas stocké) : il faut charger toutes les factures
    // correspondant aux AUTRES filtres, calculer leur DTO, filtrer par statut calculé,
    // puis paginer en mémoire.
    @Query("SELECT f FROM Facture f WHERE f.farm.id = :farmId AND f.initialisation.removed = false " +
        "AND (:hasStatut = false OR f.statut = :statut) " +
        "AND (:hasClient = false OR f.client.uniqueId = :clientUniqueId)")
    java.util.List<Facture> searchToutes(@Param("farmId") Long farmId,
                          @Param("hasStatut") boolean hasStatut, @Param("statut") Facture.StatutFacture statut,
                          @Param("hasClient") boolean hasClient, @Param("clientUniqueId") String clientUniqueId,
                          Sort sort);
}
