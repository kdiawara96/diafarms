package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.VenteReforme;

@Repository
public interface VenteReformeRepo extends JpaRepository<VenteReforme, Long> {

    Optional<VenteReforme> findByUniqueId(String uniqueId);

    // Historique des ventes réforme d'un client précis — voir ClientServiceImpl.getReport.
    @Query("SELECT v FROM VenteReforme v WHERE v.client.uniqueId = :clientUniqueId AND v.farm.id = :farmId " +
        "AND v.initialisation.removed = false ORDER BY v.date DESC")
    List<VenteReforme> findByClientUniqueIdAndFarmId(@Param("clientUniqueId") String clientUniqueId, @Param("farmId") Long farmId);

    @Query("SELECT v FROM VenteReforme v WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Page<VenteReforme> search(@Param("farmId") Long farmId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(v.nombreSujets), 0) FROM VenteReforme v " +
        "WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Integer sumSujetsVendusByFarmId(@Param("farmId") Long farmId);

    // Voir VenteOeufsRepo.sumMontantRapporteByFarmIdAndDateRange (même raisonnement :
    // montantRapporte optionnel, une vente où il n'a jamais été renseigné n'a pas de
    // dette connue et compte pour son montant théorique complet).
    // dateDebut/dateFin ATTENDUS NON-NULS — voir TransactionRepo.countByProjetIdsAndStatut
    // pour le raisonnement (le pattern "IS NULL OR" plantait Postgres sur ce type de
    // requête agrégat, quelle que soit la valeur réelle passée).
    @Query("SELECT COALESCE(SUM(COALESCE(v.montantRapporte, v.montant)), 0) FROM VenteReforme v " +
        "WHERE v.farm.id = :farmId AND v.initialisation.removed = false " +
        "AND v.date >= :dateDebut AND v.date <= :dateFin")
    Double sumMontantRapporteByFarmIdAndDateRange(@Param("farmId") Long farmId,
                                                   @Param("dateDebut") java.time.LocalDate dateDebut,
                                                   @Param("dateFin") java.time.LocalDate dateFin);
}
