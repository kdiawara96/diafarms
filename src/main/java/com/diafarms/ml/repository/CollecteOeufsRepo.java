package com.diafarms.ml.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.CollecteOeufs;

@Repository
public interface CollecteOeufsRepo extends JpaRepository<CollecteOeufs, Long> {

    Optional<CollecteOeufs> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    @Query("SELECT c FROM CollecteOeufs c LEFT JOIN c.projet p LEFT JOIN c.batiment b WHERE c.farm.id = :farmId " +
        "AND c.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId)")
    Page<CollecteOeufs> search(@Param("farmId") Long farmId,
                                @Param("projetUniqueId") String projetUniqueId,
                                @Param("batimentUniqueId") String batimentUniqueId,
                                Pageable pageable);

    // Sert au calcul du taux de ponte récent (moyenne journalière des N derniers jours).
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false AND c.date >= :since")
    Integer sumOeufsByProjetIdSince(@Param("projetId") Long projetId, @Param("since") LocalDate since);

    // Totaux vie-entière (pas fenêtrés) à l'échelle de TOUTE LA FERME : plafond global
    // de la vente d'œufs (Finance), voir VenteOeufsImpl.
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.farm.id = :farmId AND c.initialisation.removed = false")
    Integer sumOeufsCollectesByFarmId(@Param("farmId") Long farmId);

    @Query("SELECT COALESCE(SUM(c.oeufsCasses), 0) FROM CollecteOeufs c " +
        "WHERE c.farm.id = :farmId AND c.initialisation.removed = false")
    Integer sumOeufsCassesByFarmId(@Param("farmId") Long farmId);

    // Totaux vie-entière PAR PROJET : servent à répartir proportionnellement chaque
    // vente farm-wide entre les projets contributeurs (voir VenteOeufsImpl —
    // la part de chaque projet dans la vente = sa part dans le stock disponible).
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false")
    Integer sumOeufsCollectesByProjetId(@Param("projetId") Long projetId);

    @Query("SELECT COALESCE(SUM(c.oeufsCasses), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false")
    Integer sumOeufsCassesByProjetId(@Param("projetId") Long projetId);

    // Totaux PAR PROJET *dans un bâtiment de stockage précis* : servent à répartir
    // proportionnellement un transfert vers un magasin entre les projets contributeurs
    // DE CE BÂTIMENT (voir MagasinTransfertServiceImpl.disponibleParProjetDansBatimentStockage)
    // — remplace le calcul farm-wide par projet ci-dessus pour les transferts d'œufs,
    // maintenant que le stock physique est rattaché à un bâtiment, pas juste au projet.
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.batimentStockage.id = :batimentStockageId AND c.initialisation.removed = false")
    Integer sumOeufsCollectesByProjetIdAndBatimentStockageId(@Param("projetId") Long projetId, @Param("batimentStockageId") Long batimentStockageId);

    @Query("SELECT COALESCE(SUM(c.oeufsCasses), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.batimentStockage.id = :batimentStockageId AND c.initialisation.removed = false")
    Integer sumOeufsCassesByProjetIdAndBatimentStockageId(@Param("projetId") Long projetId, @Param("batimentStockageId") Long batimentStockageId);

    // Quels projets ont déjà déposé des œufs dans ce bâtiment de stockage — sert à
    // construire la carte "disponible par projet" sans avoir à connaître les projets
    // à l'avance.
    @Query("SELECT DISTINCT c.projet.id FROM CollecteOeufs c " +
        "WHERE c.batimentStockage.id = :batimentStockageId AND c.initialisation.removed = false")
    java.util.List<Long> findDistinctProjetIdsByBatimentStockageId(@Param("batimentStockageId") Long batimentStockageId);
}
