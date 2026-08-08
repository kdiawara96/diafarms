package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.DTO.VenteRepartitionReelDTO;
import com.diafarms.ml.models.VenteOeufsRepartition;

@Repository
public interface VenteOeufsRepartitionRepo extends JpaRepository<VenteOeufsRepartition, Long> {

    Optional<VenteOeufsRepartition> findByUniqueId(String uniqueId);

    // Voir VenteRepartitionReelDTO — sert à TransactionServiceImpl.getVentesReelParProjet
    // à corriger le théorique par projet au prorata réel/théorique de chaque vente.
    @Query("SELECT new com.diafarms.ml.DTO.VenteRepartitionReelDTO(r.projet.uniqueId, r.projet.code, " +
        "r.montantAttribue, r.venteOeufs.montant, r.venteOeufs.montantRapporte) " +
        "FROM VenteOeufsRepartition r " +
        "WHERE r.projet.farm.id = :farmId AND r.venteOeufs.initialisation.removed = false " +
        "AND (:dateDebut IS NULL OR r.venteOeufs.date >= :dateDebut) AND (:dateFin IS NULL OR r.venteOeufs.date <= :dateFin)")
    List<VenteRepartitionReelDTO> findReelParProjet(@Param("farmId") Long farmId,
                                                      @Param("dateDebut") java.time.LocalDate dateDebut,
                                                      @Param("dateFin") java.time.LocalDate dateFin);

    List<VenteOeufsRepartition> findByVenteOeufs_UniqueId(String venteOeufsUniqueId);

    // Déjà vendu POUR CE PROJET (toutes ventes farm-wide confondues) — sert à
    // calculer le stock d'œufs restant du projet, part qu'il peut encore
    // contribuer à une prochaine vente (voir VenteOeufsImpl.stockDisponibleParProjet).
    @Query("SELECT COALESCE(SUM(r.quantiteAttribuee), 0) FROM VenteOeufsRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteOeufs.initialisation.removed = false")
    Integer sumQuantiteByProjetId(@Param("projetId") Long projetId);

    // Déjà vendu POUR CE PROJET, DEPUIS CE MAGASIN précis — voir
    // VenteOeufsImpl.disponibleParProjetDansMagasin (stock magasin-scopé, remplace
    // l'ancien calcul farm-wide de sumQuantiteByProjetId ci-dessus pour une vente).
    @Query("SELECT COALESCE(SUM(r.quantiteAttribuee), 0) FROM VenteOeufsRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteOeufs.magasin.id = :magasinId " +
        "AND r.venteOeufs.initialisation.removed = false")
    Integer sumQuantiteByProjetIdAndMagasinId(@Param("projetId") Long projetId, @Param("magasinId") Long magasinId);

    // Total vendu DEPUIS ce magasin, tous projets contributeurs confondus — pour
    // l'aperçu global de stock du magasin (StockMagasinDTO).
    @Query("SELECT COALESCE(SUM(r.quantiteAttribuee), 0) FROM VenteOeufsRepartition r " +
        "WHERE r.venteOeufs.magasin.id = :magasinId AND r.venteOeufs.initialisation.removed = false")
    Integer sumQuantiteByMagasinId(@Param("magasinId") Long magasinId);
}
