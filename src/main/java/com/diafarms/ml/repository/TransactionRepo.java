package com.diafarms.ml.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Transaction;

@Repository
public interface TransactionRepo extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByUniqueId(String uniqueId);

    // Retrouve la transaction "recette" générée automatiquement par une vente
    // (VenteOeufs/VenteReforme), pour la faire suivre (montant, removed) quand la
    // vente source est modifiée ou supprimée — voir TransactionService.createFromSource.
    Optional<Transaction> findBySourceUniqueId(String sourceUniqueId);

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
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) " +
        "AND (:dateFin IS NULL OR t.date <= :dateFin) " +
        "AND (:search IS NULL OR LOWER(t.ref) LIKE :search " +
        "OR LOWER(t.description) LIKE :search " +
        "OR LOWER(t.categorie) LIKE :search)")
    Page<Transaction> search(@Param("farmId") Long farmId,
                              @Param("type") TypeTransaction type,
                              @Param("statut") StatutTransaction statut,
                              @Param("projetUniqueId") String projetUniqueId,
                              @Param("dateDebut") LocalDate dateDebut,
                              @Param("dateFin") LocalDate dateFin,
                              @Param("search") String search,
                              Pageable pageable);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false AND t.statut = :statut")
    long countByFarmIdAndStatut(@Param("farmId") Long farmId, @Param("statut") StatutTransaction statut);

    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.type = :type")
    Double sumMontantValideByType(@Param("farmId") Long farmId, @Param("type") TypeTransaction type);

    // Recette réelle des ventes d'œufs/réforme (Comptabilité) : les transactions
    // "entrée" générées automatiquement par une vente (voir
    // TransactionService.createFromSource) portent ce sourceType, distinct d'une
    // transaction "entrée" saisie manuellement.
    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.sourceType = :sourceType")
    Double sumMontantValideBySourceType(@Param("farmId") Long farmId, @Param("sourceType") SourceTransaction sourceType);

    // Chiffre d'affaires réel d'un projet (voir ProjetsDTO.fromEntity/fromEntityList) :
    // uniquement les transactions rattachées directement (t.projet), pas celles
    // "communes" concernant plusieurs projets (projetsConcernes) — sinon un JOIN sur
    // cette relation ManyToMany ferait du fan-out et fausserait la somme.
    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.projet.id = :projetId " +
        "AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.type = :type")
    Double sumMontantValideByProjetIdAndType(@Param("projetId") Long projetId, @Param("type") TypeTransaction type);

    // ================================================================================
    // Vue "financier" (Comptabilité restreinte) : un FINANCIER ne voit que les
    // transactions des projets où il est responsableFinance (voir
    // TransactionServiceImpl.resolveProjetIdsScope) — jamais les transactions
    // "Commune" (projet_id NULL), qui ne sont assignées à aucun projet précis. Un
    // ADMIN peut se placer dans cette même vue pour un financier choisi (filtre
    // "voir comme"), avec une période optionnelle en plus.
    // ================================================================================

    @Query("SELECT DISTINCT t FROM Transaction t JOIN t.projet p WHERE p.id IN :projetIds " +
        "AND t.initialisation.removed = false " +
        "AND (:type IS NULL OR t.type = :type) " +
        "AND (:statut IS NULL OR t.statut = :statut) " +
        "AND (:projetUniqueId IS NULL OR p.uniqueId = :projetUniqueId) " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) " +
        "AND (:dateFin IS NULL OR t.date <= :dateFin) " +
        "AND (:search IS NULL OR LOWER(t.ref) LIKE :search " +
        "OR LOWER(t.description) LIKE :search " +
        "OR LOWER(t.categorie) LIKE :search)")
    Page<Transaction> searchScoped(@Param("projetIds") List<Long> projetIds,
                                    @Param("type") TypeTransaction type,
                                    @Param("statut") StatutTransaction statut,
                                    @Param("projetUniqueId") String projetUniqueId,
                                    @Param("dateDebut") LocalDate dateDebut,
                                    @Param("dateFin") LocalDate dateFin,
                                    @Param("search") String search,
                                    Pageable pageable);

    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.projet.id IN :projetIds AND t.initialisation.removed = false " +
        "AND t.statut = :statut " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) AND (:dateFin IS NULL OR t.date <= :dateFin)")
    long countByProjetIdsAndStatut(@Param("projetIds") List<Long> projetIds, @Param("statut") StatutTransaction statut,
                                    @Param("dateDebut") LocalDate dateDebut, @Param("dateFin") LocalDate dateFin);

    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.projet.id IN :projetIds AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.type = :type " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) AND (:dateFin IS NULL OR t.date <= :dateFin)")
    Double sumMontantValideByProjetIdsAndType(@Param("projetIds") List<Long> projetIds, @Param("type") TypeTransaction type,
                                               @Param("dateDebut") LocalDate dateDebut, @Param("dateFin") LocalDate dateFin);

    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.projet.id IN :projetIds AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.sourceType = :sourceType " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) AND (:dateFin IS NULL OR t.date <= :dateFin)")
    Double sumMontantValideByProjetIdsAndSourceType(@Param("projetIds") List<Long> projetIds, @Param("sourceType") SourceTransaction sourceType,
                                                     @Param("dateDebut") LocalDate dateDebut, @Param("dateFin") LocalDate dateFin);

    // Farm-wide avec période optionnelle — utilisée par l'ADMIN quand il filtre par
    // date sans restreindre à un financier ("tous les financiers").
    @Query("SELECT COUNT(t) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false " +
        "AND t.statut = :statut " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) AND (:dateFin IS NULL OR t.date <= :dateFin)")
    long countByFarmIdAndStatutAndDateRange(@Param("farmId") Long farmId, @Param("statut") StatutTransaction statut,
                                             @Param("dateDebut") LocalDate dateDebut, @Param("dateFin") LocalDate dateFin);

    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.type = :type " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) AND (:dateFin IS NULL OR t.date <= :dateFin)")
    Double sumMontantValideByTypeAndDateRange(@Param("farmId") Long farmId, @Param("type") TypeTransaction type,
                                               @Param("dateDebut") LocalDate dateDebut, @Param("dateFin") LocalDate dateFin);

    @Query("SELECT COALESCE(SUM(t.montant), 0.0) FROM Transaction t WHERE t.farm.id = :farmId AND t.initialisation.removed = false " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE AND t.sourceType = :sourceType " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) AND (:dateFin IS NULL OR t.date <= :dateFin)")
    Double sumMontantValideBySourceTypeAndDateRange(@Param("farmId") Long farmId, @Param("sourceType") SourceTransaction sourceType,
                                                     @Param("dateDebut") LocalDate dateDebut, @Param("dateFin") LocalDate dateFin);

    // ================================================================================
    // Vue "vendeur" (page Ventes restreinte) : un axe différent de la vue "financier"
    // ci-dessus — restreint par QUI A CRÉÉ la transaction (t.creePar), pas par le
    // projet. Un FINANCIER pur ne voit que ses propres ventes ; un ADMIN peut choisir
    // n'importe quel vendeur (ou aucun = tout le monde) — voir
    // TransactionServiceImpl.resolveVendeurScopeForList.
    // ================================================================================

    @Query("SELECT DISTINCT t FROM Transaction t WHERE t.farm.id = :farmId AND t.creePar.uniqueId = :creeParUniqueId " +
        "AND t.initialisation.removed = false " +
        "AND (:type IS NULL OR t.type = :type) " +
        "AND (:statut IS NULL OR t.statut = :statut) " +
        "AND (:dateDebut IS NULL OR t.date >= :dateDebut) " +
        "AND (:dateFin IS NULL OR t.date <= :dateFin) " +
        "AND (:search IS NULL OR LOWER(t.ref) LIKE :search " +
        "OR LOWER(t.description) LIKE :search " +
        "OR LOWER(t.categorie) LIKE :search)")
    Page<Transaction> searchByCreePar(@Param("farmId") Long farmId,
                                       @Param("creeParUniqueId") String creeParUniqueId,
                                       @Param("type") TypeTransaction type,
                                       @Param("statut") StatutTransaction statut,
                                       @Param("dateDebut") LocalDate dateDebut,
                                       @Param("dateFin") LocalDate dateFin,
                                       @Param("search") String search,
                                       Pageable pageable);

    // Transactions rejetées créées par un utilisateur donné — sert à le notifier du
    // rejet (voir NotificationServiceImpl.addRejetNotification), pour qu'un
    // FINANCIER apprenne qu'une de ses ventes a été rejetée sans avoir à parcourir
    // Comptabilité (auquel il n'a de toute façon pas accès en détail).
    @Query("SELECT t FROM Transaction t WHERE t.creePar.id = :creeParId " +
        "AND t.statut = com.diafarms.ml.enums.StatutTransaction.REJETE AND t.initialisation.removed = false")
    List<Transaction> findRejeteesByCreeParId(@Param("creeParId") Long creeParId);
}
