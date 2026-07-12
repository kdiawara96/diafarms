package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Transaction;

@Repository
public interface TransactionRepo extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByUniqueId(String uniqueId);

    @Query("SELECT COUNT(t) > 0 FROM Transaction t WHERE t.ref = :ref")
    boolean existsByRef(@Param("ref") String ref);

    // LEFT JOIN explicite sur t.projet : un chemin implicite "t.projet.uniqueId" dans le
    // WHERE forcerait un INNER JOIN au niveau SQL et ferait disparaître TOUTES les
    // transactions "Commune" (projet_id NULL) de la liste, même sans filtre projet actif.
    @Query("SELECT DISTINCT t FROM Transaction t LEFT JOIN t.projet p LEFT JOIN t.projetsConcernes pc WHERE t.farm.id = :farmId " +
        "AND t.initialisation.removed = false " +
        "AND (:type IS NULL OR t.type = :type) " +
        "AND (:statut IS NULL OR t.statut = :statut) " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId OR pc.uniqueId = :projetUniqueId) " +
        "AND (:search IS NULL OR LOWER(t.ref) LIKE :search " +
        "OR LOWER(t.description) LIKE :search " +
        "OR LOWER(t.categorie) LIKE :search)")
    Page<Transaction> search(@Param("farmId") Long farmId,
                              @Param("type") TypeTransaction type,
                              @Param("statut") StatutTransaction statut,
                              @Param("projetUniqueId") String projetUniqueId,
                              @Param("search") String search,
                              Pageable pageable);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false AND t.statut = :statut")
    long countByFarmIdAndStatut(@Param("farmId") Long farmId, @Param("statut") StatutTransaction statut);

    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.type = :type")
    Double sumMontantValideByType(@Param("farmId") Long farmId, @Param("type") TypeTransaction type);
}
