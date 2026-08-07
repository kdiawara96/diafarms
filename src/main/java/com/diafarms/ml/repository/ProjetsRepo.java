package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Projets;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

@Repository
public interface ProjetsRepo extends JpaRepository<Projets, Long> {
    
   Optional<Projets> findByUniqueId(String uniqueId);

   List<Projets> findByUniqueIdIn(List<String> uniqueIds);

   @Query("SELECT p FROM Projets p WHERE p.farm.id = :farmId " +
        "AND p.initialisation.removed = false " +
        "AND (:isArchive IS NULL OR p.initialisation.archive = :isArchive) " +
        "AND (:search IS NULL OR LOWER(p.titre) LIKE :search " +
        "OR LOWER(p.uniqueId) LIKE :search " +
        "OR LOWER(p.code) LIKE :search " +
        "OR LOWER(p.responsable) LIKE :search " +
        "OR LOWER(p.fournisseurs_poussins) LIKE :search)")
    Page<Projets> searchProjets(@Param("farmId") Long farmId, 
                                @Param("isArchive") Boolean isArchive, 
                                @Param("search") String search, 
                                Pageable pageable);

    @Query("SELECT COUNT(p) > 0 FROM Projets p WHERE p.code = :code")
    boolean existsByCode(@Param("code") String code);

     // 🔥 Récupère tous les projets non supprimés
    List<Projets> findByInitialisation_RemovedFalse();

    // Utilisés par /projets/select : un ADMIN voit tous les projets de sa ferme, un
    // PRODUCTEUR/FINANCIER ne voit que ceux où il est explicitement désigné responsable
    // (auparavant /projets/select ne filtrait ni par ferme ni par affectation : n'importe
    // quel utilisateur authentifié voyait tous les projets de toutes les fermes).
    @Query("SELECT p FROM Projets p WHERE p.farm.id = :farmId AND p.initialisation.removed = false")
    List<Projets> findAllActiveByFarm(@Param("farmId") Long farmId);

    @Query("SELECT p FROM Projets p WHERE p.farm.id = :farmId AND p.initialisation.removed = false " +
           "AND (p.responsableProduction.uniqueId = :userUniqueId OR p.responsableFinance.uniqueId = :userUniqueId)")
    List<Projets> findAssignedToUser(@Param("farmId") Long farmId, @Param("userUniqueId") String userUniqueId);

    // Variante triée/limitée de findAssignedToUser, pour la modale "Profil & Accès Mobile
    // Utilisateur" côté web (derniers projets associés) — actifs ET archivés inclus
    // volontairement (seul le soft-delete "removed" est exclu), le statut actif/inactif
    // étant affiché tel quel plutôt que filtré.
    @Query("SELECT p FROM Projets p WHERE p.farm.id = :farmId AND p.initialisation.removed = false " +
           "AND (p.responsableProduction.uniqueId = :userUniqueId OR p.responsableFinance.uniqueId = :userUniqueId) " +
           "ORDER BY p.initialisation.createdAt DESC")
    List<Projets> findRecentAssignedToUser(@Param("farmId") Long farmId, @Param("userUniqueId") String userUniqueId, Pageable pageable);

    // Périmètre financier strict (Comptabilité restreinte) : uniquement les projets où
    // l'utilisateur est responsableFinance, pas responsableProduction — voir
    // TransactionServiceImpl.resolveProjetIdsScope, qui l'utilise pour qu'un FINANCIER
    // (ou l'ADMIN filtrant "voir comme un financier") ne voie que ce qui relève de sa
    // responsabilité financière.
    @Query("SELECT p.id FROM Projets p WHERE p.farm.id = :farmId AND p.initialisation.removed = false " +
           "AND p.responsableFinance.uniqueId = :userUniqueId")
    List<Long> findProjetIdsAssignedAsFinanceToUser(@Param("farmId") Long farmId, @Param("userUniqueId") String userUniqueId);
}
