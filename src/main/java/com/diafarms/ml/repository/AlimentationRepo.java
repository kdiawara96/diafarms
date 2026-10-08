package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Alimentation;

@Repository
public interface AlimentationRepo extends JpaRepository<Alimentation, Long> {

    Optional<Alimentation> findByUniqueId(String uniqueId);
    @Query("SELECT a FROM Alimentation a WHERE a.projet.uniqueId = :uniqueId AND a.initialisation.removed = false")
    List<Alimentation> findByProjetUniqueIdAndInitialisationRemovedFalse(@Param("uniqueId") String uniqueId);
    @Query("SELECT a FROM Alimentation a WHERE a.uniqueId = :uniqueId AND a.initialisation.removed = false")
    Optional<Alimentation> findByUniqueIdAndInitialisationRemovedFalse(@Param("uniqueId") String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel, valeur
    // factice non nulle quand hasX = false : jamais de "(:x IS NULL OR ...)" (plantage
    // Postgres dès que le type du paramètre nul est inconnu, voir CommandeRepo.search).
    @Query("SELECT a FROM Alimentation a LEFT JOIN a.projet p LEFT JOIN a.batiment b WHERE a.farm.id = :farmId " +
        "AND a.initialisation.removed = false " +
        "AND (:hasProjet = false OR p.uniqueId = :projetUniqueId) " +
        "AND (:hasBatiment = false OR b.uniqueId = :batimentUniqueId) " +
        "AND (:hasSearch = false OR LOWER(a.nomAliment) LIKE :search OR LOWER(a.observations) LIKE :search)")
    Page<Alimentation> search(@Param("farmId") Long farmId,
                               @Param("hasProjet") boolean hasProjet, @Param("projetUniqueId") String projetUniqueId,
                               @Param("hasBatiment") boolean hasBatiment, @Param("batimentUniqueId") String batimentUniqueId,
                               @Param("hasSearch") boolean hasSearch, @Param("search") String search,
                               Pageable pageable);

    @Query("SELECT COALESCE(SUM(a.quantiteKg), 0.0) FROM Alimentation a " +
        "WHERE a.projet.id = :projetId AND a.initialisation.removed = false")
    Double sumAcheteByProjetId(@Param("projetId") Long projetId);

    // Sert à calculer le prix moyen au kg d'un projet (coût / quantité achetée), pour
    // valoriser un transfert de stock restant vers un autre projet à la clôture.
    @Query("SELECT COALESCE(SUM(a.coutTotal), 0.0) FROM Alimentation a " +
        "WHERE a.projet.id = :projetId AND a.initialisation.removed = false")
    Double sumCoutAcheteByProjetId(@Param("projetId") Long projetId);
}
