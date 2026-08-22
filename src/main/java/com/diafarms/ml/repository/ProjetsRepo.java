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

   @Query("SELECT p FROM Projets p LEFT JOIN p.responsable r WHERE p.farm.id = :farmId " +
        "AND p.initialisation.removed = false " +
        "AND (:isArchive IS NULL OR p.initialisation.archive = :isArchive) " +
        "AND (:search IS NULL OR LOWER(p.titre) LIKE :search " +
        "OR LOWER(p.uniqueId) LIKE :search " +
        "OR LOWER(p.code) LIKE :search " +
        "OR LOWER(r.fullName) LIKE :search " +
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

    // LEFT JOIN explicites obligatoires ici — trouvé en creusant un bug réel de
    // synchronisation mobile ("le projet ne vient pas") : une navigation implicite
    // (p.responsableProduction.uniqueId) génère un INNER JOIN par défaut en JPQL. Avec
    // 3 associations OPTIONNELLES combinées en OR (responsableProduction/Finance/
    // responsable), dès qu'UNE SEULE des trois est NULL pour un projet (cas courant :
    // "responsable" générique jamais renseigné), l'INNER JOIN correspondant élimine le
    // projet ENTIER de la requête — même si responsableProduction/Finance matchaient
    // bien. Confirmé : la requête native équivalente (LEFT JOIN) trouvait le projet,
    // celle-ci (implicite) non. Voir aussi findRecentAssignedToUser, qui "marchait" par
    // simple coïncidence (elle ne teste pas p.responsable, jamais concernée par ce bug).
    @Query("SELECT p FROM Projets p " +
           "LEFT JOIN p.responsableProduction rp " +
           "LEFT JOIN p.responsableFinance rf " +
           "LEFT JOIN p.responsable r " +
           "WHERE p.farm.id = :farmId AND p.initialisation.removed = false " +
           "AND (rp.uniqueId = :userUniqueId OR rf.uniqueId = :userUniqueId OR r.uniqueId = :userUniqueId)")
    List<Projets> findAssignedToUser(@Param("farmId") Long farmId, @Param("userUniqueId") String userUniqueId);

    // Variante triée/limitée de findAssignedToUser, pour la modale "Profil & Accès Mobile
    // Utilisateur" côté web (derniers projets associés) — actifs ET archivés inclus
    // volontairement (seul le soft-delete "removed" est exclu), le statut actif/inactif
    // étant affiché tel quel plutôt que filtré.
    // LEFT JOIN explicites — même raison que findAssignedToUser ci-dessus : sans ça, un
    // projet avec responsableProduction OU responsableFinance null (mais pas les deux)
    // disparaîtrait à tort de la liste malgré une correspondance sur l'autre champ.
    @Query("SELECT p FROM Projets p " +
           "LEFT JOIN p.responsableProduction rp " +
           "LEFT JOIN p.responsableFinance rf " +
           "WHERE p.farm.id = :farmId AND p.initialisation.removed = false " +
           "AND (rp.uniqueId = :userUniqueId OR rf.uniqueId = :userUniqueId) " +
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

    // Périmètre RESPONSABLE (gère/clôture, valide/rejette comptabilité+ventes) —
    // uniquement les projets où l'utilisateur est LE responsable (champ dédié, pas
    // responsableProduction/responsableFinance), voir TransactionServiceImpl.
    @Query("SELECT p.id FROM Projets p WHERE p.farm.id = :farmId AND p.initialisation.removed = false " +
           "AND p.responsable.uniqueId = :userUniqueId")
    List<Long> findProjetIdsAssignedAsResponsableToUser(@Param("farmId") Long farmId, @Param("userUniqueId") String userUniqueId);
}
