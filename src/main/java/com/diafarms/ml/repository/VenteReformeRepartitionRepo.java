package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.DTO.RepartitionRatioDTO;
import com.diafarms.ml.DTO.VenteRepartitionReelDTO;
import com.diafarms.ml.models.VenteReformeRepartition;

@Repository
public interface VenteReformeRepartitionRepo extends JpaRepository<VenteReformeRepartition, Long> {

    Optional<VenteReformeRepartition> findByUniqueId(String uniqueId);

    // Voir VenteOeufsRepartitionRepo.findRatiosByUniqueIds (même raisonnement, y compris
    // le LEFT JOIN explicite sur client, nullable).
    @Query("SELECT new com.diafarms.ml.DTO.RepartitionRatioDTO(r.uniqueId, v.montant, v.montantRapporte, c.nom) " +
        "FROM VenteReformeRepartition r JOIN r.venteReforme v LEFT JOIN v.client c WHERE r.uniqueId IN :uniqueIds")
    List<RepartitionRatioDTO> findRatiosByUniqueIds(@Param("uniqueIds") List<String> uniqueIds);

    List<VenteReformeRepartition> findByVenteReforme_UniqueId(String venteReformeUniqueId);

    // Voir VenteOeufsRepartitionRepo.findReelParProjet (même raisonnement, y compris
    // la jointure Transaction VALIDE).
    @Query("SELECT new com.diafarms.ml.DTO.VenteRepartitionReelDTO(r.projet.uniqueId, r.projet.code, " +
        "r.montantAttribue, r.venteReforme.montant, r.venteReforme.montantRapporte) " +
        "FROM VenteReformeRepartition r, Transaction t " +
        "WHERE t.sourceUniqueId = r.uniqueId AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE " +
        "AND r.projet.farm.id = :farmId AND r.venteReforme.initialisation.removed = false " +
        "AND (:dateDebut IS NULL OR r.venteReforme.date >= :dateDebut) AND (:dateFin IS NULL OR r.venteReforme.date <= :dateFin)")
    List<VenteRepartitionReelDTO> findReelParProjet(@Param("farmId") Long farmId,
                                                      @Param("dateDebut") java.time.LocalDate dateDebut,
                                                      @Param("dateFin") java.time.LocalDate dateFin);

    // Déjà vendu POUR CE PROJET — voir VenteOeufsRepartitionRepo.sumQuantiteByProjetId.
    @Query("SELECT COALESCE(SUM(r.nombreSujetsAttribue), 0) FROM VenteReformeRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteReforme.initialisation.removed = false")
    Integer sumSujetsByProjetId(@Param("projetId") Long projetId);

    // Déjà vendu POUR CE PROJET, DEPUIS CE MAGASIN précis — voir
    // VenteReformeImpl.disponibleParProjetDansMagasin.
    @Query("SELECT COALESCE(SUM(r.nombreSujetsAttribue), 0) FROM VenteReformeRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteReforme.magasin.id = :magasinId " +
        "AND r.venteReforme.initialisation.removed = false")
    Integer sumSujetsByProjetIdAndMagasinId(@Param("projetId") Long projetId, @Param("magasinId") Long magasinId);

    // Total vendu DEPUIS ce magasin, tous projets contributeurs confondus — pour
    // l'aperçu global de stock du magasin (StockMagasinDTO).
    @Query("SELECT COALESCE(SUM(r.nombreSujetsAttribue), 0) FROM VenteReformeRepartition r " +
        "WHERE r.venteReforme.magasin.id = :magasinId AND r.venteReforme.initialisation.removed = false")
    Integer sumSujetsByMagasinId(@Param("magasinId") Long magasinId);
}
