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
    @Query("SELECT r FROM Reforme r LEFT JOIN r.projet p LEFT JOIN r.batiment b WHERE r.farm.id = :farmId " +
        "AND r.initialisation.removed = false " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:batimentUniqueId IS NULL OR b.uniqueId = :batimentUniqueId) " +
        "AND (:search IS NULL OR LOWER(r.cause) LIKE :search)")
    Page<Reforme> search(@Param("farmId") Long farmId,
                          @Param("projetUniqueId") String projetUniqueId,
                          @Param("batimentUniqueId") String batimentUniqueId,
                          @Param("search") String search,
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
}
