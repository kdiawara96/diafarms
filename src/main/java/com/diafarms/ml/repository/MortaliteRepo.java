package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Mortalite;

@Repository
public interface MortaliteRepo extends JpaRepository<Mortalite, Long> {

    Optional<Mortalite> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel, valeur
    // factice non nulle quand hasX = false : jamais de "(:x IS NULL OR ...)" (plantage
    // Postgres dès que le type du paramètre nul est inconnu, voir CommandeRepo.search).
    @Query("SELECT m FROM Mortalite m LEFT JOIN m.projet p LEFT JOIN m.batiment b WHERE m.farm.id = :farmId " +
        "AND m.initialisation.removed = false " +
        "AND (:hasProjet = false OR p.uniqueId = :projetUniqueId) " +
        "AND (:hasBatiment = false OR b.uniqueId = :batimentUniqueId) " +
        "AND (:hasSearch = false OR LOWER(m.cause) LIKE :search)")
    Page<Mortalite> search(@Param("farmId") Long farmId,
                            @Param("hasProjet") boolean hasProjet, @Param("projetUniqueId") String projetUniqueId,
                            @Param("hasBatiment") boolean hasBatiment, @Param("batimentUniqueId") String batimentUniqueId,
                            @Param("hasSearch") boolean hasSearch, @Param("search") String search,
                            Pageable pageable);

    @Query("SELECT COALESCE(SUM(m.nombreMorts), 0) FROM Mortalite m " +
        "WHERE m.projet.id = :projetId AND m.initialisation.removed = false")
    Integer sumMortsByProjetId(@Param("projetId") Long projetId);

    // Mortalité PAR BÂTIMENT — sert à calculer l'effectif vivant d'un bâtiment précis
    // (voir CollecteOeufsImpl.effectifVivantBatiment), distinct du total du projet.
    @Query("SELECT COALESCE(SUM(m.nombreMorts), 0) FROM Mortalite m " +
        "WHERE m.batiment.id = :batimentId AND m.initialisation.removed = false")
    Integer sumMortsByBatimentId(@Param("batimentId") Long batimentId);

    // Voir CollecteOeufsRepo.findAllByProjetId — même usage pour RapportJournalierServiceImpl.
    @Query("SELECT m FROM Mortalite m WHERE m.projet.id = :projetId AND m.initialisation.removed = false")
    java.util.List<Mortalite> findAllByProjetId(@Param("projetId") Long projetId);

    // Vue plan : mortalité cumulée PAR BÂTIMENT pour toute une ferme, en une requête
    // (mêmes règles que sumMortsByBatimentId). Lignes [batimentId, total].
    @Query("SELECT m.batiment.id, COALESCE(SUM(m.nombreMorts), 0) FROM Mortalite m " +
        "WHERE m.batiment.farm.id = :farmId AND m.initialisation.removed = false GROUP BY m.batiment.id")
    java.util.List<Object[]> sumMortsParBatimentDeLaFerme(@Param("farmId") Long farmId);

    // Main-d'œuvre (MainOeuvreService) : morts cumulés par projet jusqu'à une date, pour une ferme.
    @Query("SELECT m.projet.id, COALESCE(SUM(m.nombreMorts), 0) FROM Mortalite m WHERE m.projet.farm.id = :farmId "
        + "AND m.initialisation.removed = false AND m.date <= :jusqua GROUP BY m.projet.id")
    java.util.List<Object[]> sumMortsParProjetJusqua(@Param("farmId") Long farmId, @Param("jusqua") java.time.LocalDate jusqua);
}
