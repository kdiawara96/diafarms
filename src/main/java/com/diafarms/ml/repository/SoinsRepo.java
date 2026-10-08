package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Soins;

@Repository
public interface SoinsRepo extends JpaRepository<Soins, Long> {

    Optional<Soins> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel, valeur
    // factice non nulle quand hasX = false : jamais de "(:x IS NULL OR ...)" (plantage
    // Postgres dès que le type du paramètre nul est inconnu, voir CommandeRepo.search).
    @Query("SELECT s FROM Soins s LEFT JOIN s.projet p LEFT JOIN s.batiment b WHERE s.farm.id = :farmId " +
        "AND s.initialisation.removed = false " +
        "AND (:hasProjet = false OR p.uniqueId = :projetUniqueId) " +
        "AND (:hasBatiment = false OR b.uniqueId = :batimentUniqueId) " +
        "AND (:hasType = false OR s.type = :type) " +
        "AND (:hasSearch = false OR LOWER(s.produit) LIKE :search OR LOWER(s.observations) LIKE :search)")
    Page<Soins> search(@Param("farmId") Long farmId,
                        @Param("hasProjet") boolean hasProjet, @Param("projetUniqueId") String projetUniqueId,
                        @Param("hasBatiment") boolean hasBatiment, @Param("batimentUniqueId") String batimentUniqueId,
                        @Param("hasType") boolean hasType, @Param("type") com.diafarms.ml.enums.TypeSoin type,
                        @Param("hasSearch") boolean hasSearch, @Param("search") String search,
                        Pageable pageable);

    @Query("SELECT s FROM Soins s WHERE s.projet.uniqueId = :projetUniqueId AND s.initialisation.removed = false")
    List<Soins> findByProjetUniqueIdAndInitialisationRemovedFalse(@Param("projetUniqueId") String projetUniqueId);

    // Stock de médicaments (MedicamentService) : soins pris dans le stock d'un projet.
    @Query("SELECT s FROM Soins s WHERE s.projet.id = :projetId AND s.depuisStock = true AND s.initialisation.removed = false")
    List<Soins> findDepuisStockByProjetId(@Param("projetId") Long projetId);
}
