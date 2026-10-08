package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Reforme;

@Repository
public interface ReformeRepo extends JpaRepository<Reforme, Long> {

    Optional<Reforme> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel, valeur
    // factice non nulle quand hasX = false : jamais de "(:x IS NULL OR ...)" (plantage
    // Postgres dès que le type du paramètre nul est inconnu, voir CommandeRepo.search).
    @Query("SELECT r FROM Reforme r LEFT JOIN r.projet p LEFT JOIN r.batiment b WHERE r.farm.id = :farmId " +
        "AND r.initialisation.removed = false " +
        "AND (:hasProjet = false OR p.uniqueId = :projetUniqueId) " +
        "AND (:hasBatiment = false OR b.uniqueId = :batimentUniqueId) " +
        "AND (:hasSearch = false OR LOWER(r.cause) LIKE :search)")
    Page<Reforme> search(@Param("farmId") Long farmId,
                          @Param("hasProjet") boolean hasProjet, @Param("projetUniqueId") String projetUniqueId,
                          @Param("hasBatiment") boolean hasBatiment, @Param("batimentUniqueId") String batimentUniqueId,
                          @Param("hasSearch") boolean hasSearch, @Param("search") String search,
                          Pageable pageable);

    @Query("SELECT COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r " +
        "WHERE r.projet.id = :projetId AND r.initialisation.removed = false")
    Integer sumSujetsByProjetId(@Param("projetId") Long projetId);

    // Réforme PAR BÂTIMENT — sert à calculer l'effectif vivant d'un bâtiment précis
    // (voir CollecteOeufsImpl.effectifVivantBatiment), distinct du total du projet.
    @Query("SELECT COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r " +
        "WHERE r.batiment.id = :batimentId AND r.initialisation.removed = false")
    Integer sumSujetsByBatimentId(@Param("batimentId") Long batimentId);

    // Total réformé de toute la ferme (tous projets confondus) — plafonne la vente
    // réforme côté Finance (VenteReformeImpl), qui n'est pas rattachée à un projet.
    @Query("SELECT COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r " +
        "WHERE r.farm.id = :farmId AND r.initialisation.removed = false")
    Integer sumSujetsByFarmId(@Param("farmId") Long farmId);

    // Voir CollecteOeufsRepo.findAllByProjetId — même usage pour RapportJournalierServiceImpl.
    @Query("SELECT r FROM Reforme r WHERE r.projet.id = :projetId AND r.initialisation.removed = false")
    java.util.List<Reforme> findAllByProjetId(@Param("projetId") Long projetId);

    // Vue plan : sujets réformés PAR BÂTIMENT pour toute une ferme, en une requête
    // (mêmes règles que sumSujetsByBatimentId). Lignes [batimentId, total].
    @Query("SELECT r.batiment.id, COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r " +
        "WHERE r.batiment.farm.id = :farmId AND r.initialisation.removed = false GROUP BY r.batiment.id")
    java.util.List<Object[]> sumSujetsParBatimentDeLaFerme(@Param("farmId") Long farmId);

    // Main-d'œuvre (MainOeuvreService) : sujets réformés par projet jusqu'à une date, pour une ferme.
    @Query("SELECT r.projet.id, COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r WHERE r.projet.farm.id = :farmId "
        + "AND r.initialisation.removed = false AND r.date <= :jusqua GROUP BY r.projet.id")
    java.util.List<Object[]> sumSujetsParProjetJusqua(@Param("farmId") Long farmId, @Param("jusqua") java.time.LocalDate jusqua);

    // Réformés du projet sans compter une réforme donnée (sa nouvelle valeur est ajoutée
    // par l'appelant : indépendant de l'état, enregistré ou non, de l'entité modifiée).
    @Query("SELECT COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r " +
        "WHERE r.projet.id = :projetId AND r.id <> :reformeId AND r.initialisation.removed = false")
    Integer sumSujetsByProjetIdHors(@Param("projetId") Long projetId, @Param("reformeId") Long reformeId);

    // Reprise des transferts automatiques (ReformeTransfertsManquantsService) : projets
    // de la ferme ayant au moins une réforme active.
    @Query("SELECT DISTINCT r.projet.id FROM Reforme r WHERE r.projet.farm.id = :farmId AND r.initialisation.removed = false")
    java.util.List<Long> findDistinctProjetIdsByFarmId(@Param("farmId") Long farmId);

    // Réformés d'un projet dans UN magasin de stockage (ou sans magasin de stockage : réformes
    // anciennes), sans compter une réforme donnée (-1 = aucune) : base du stock de réformés
    // d'un magasin de stockage, voir ReformeStockage.
    @Query("SELECT COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r " +
        "WHERE r.projet.id = :projetId AND r.magasinStockage.id = :magasinStockageId AND r.id <> :reformeId " +
        "AND r.initialisation.removed = false")
    Integer sumSujetsByProjetIdAndMagasinStockageIdHors(@Param("projetId") Long projetId,
                                                        @Param("magasinStockageId") Long magasinStockageId,
                                                        @Param("reformeId") Long reformeId);

    @Query("SELECT COALESCE(SUM(r.nombreSujets), 0) FROM Reforme r " +
        "WHERE r.projet.id = :projetId AND r.magasinStockage IS NULL AND r.id <> :reformeId " +
        "AND r.initialisation.removed = false")
    Integer sumSujetsSansStockageByProjetIdHors(@Param("projetId") Long projetId, @Param("reformeId") Long reformeId);

    // Projets ayant des réformés actifs dans ce magasin de stockage (transfert manuel
    // réparti entre projets, comme les œufs : MagasinTransfertServiceImpl).
    @Query("SELECT DISTINCT r.projet.id FROM Reforme r WHERE r.magasinStockage.id = :magasinStockageId " +
        "AND r.initialisation.removed = false")
    java.util.List<Long> findDistinctProjetIdsByMagasinStockageId(@Param("magasinStockageId") Long magasinStockageId);

    // Reprise (ReformeTransfertsManquantsService) : réformes actives d'un projet sans
    // magasin de stockage ni transfert lié, les plus récentes d'abord.
    @Query("SELECT r FROM Reforme r WHERE r.projet.id = :projetId AND r.initialisation.removed = false " +
        "AND r.magasinStockage IS NULL " +
        "AND NOT EXISTS (SELECT t.id FROM MagasinTransfert t WHERE t.reforme.id = r.id) " +
        "ORDER BY r.date DESC, r.id DESC")
    java.util.List<Reforme> findSansStockageNiTransfertByProjetId(@Param("projetId") Long projetId);
}
