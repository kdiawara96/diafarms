package com.diafarms.ml.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    // Pas d'ORDER BY ici : le tri vient du Pageable, comme CommandeRepo.search.
    @Query("SELECT f FROM Facture f WHERE f.farm.id = :farmId AND f.initialisation.removed = false " +
        "AND (:statut IS NULL OR f.statut = :statut) " +
        "AND (:clientUniqueId IS NULL OR f.client.uniqueId = :clientUniqueId)")
    Page<Facture> search(@Param("farmId") Long farmId,
                          @Param("statut") Facture.StatutFacture statut,
                          @Param("clientUniqueId") String clientUniqueId,
                          Pageable pageable);
}
