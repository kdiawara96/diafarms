package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Logs;

@Repository
public interface LogsRepo extends JpaRepository<Logs, Long>{

    Optional<Logs> findByUniqueId(String uniqueId);

    Page<Logs> findAllByInitialisationRemovedFalseAndInitialisationArchiveFalse(Pageable pageable);

    Page<Logs> findAllByInitialisationRemovedTrue(Pageable pageable);

    Page<Logs> findAllByEntityIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(Pageable pageable, Long entityId);

    Page<Logs> findAllByEntityIdAndInitialisationRemovedTrue(Pageable pageable, Long entityId);

    Page<Logs> findAllByEntityTypeAndInitialisationRemovedFalseAndInitialisationArchiveFalse(Pageable pageable, String entityType);

      // ✅ CORRIGÉ : retourne Page + utilise Pageable pour le tri
    @Query("SELECT l FROM Logs l " +
        "WHERE l.initialisation.removed = false " +
        "AND l.initialisation.archive = false " +
        "AND (" +
        "   l.action LIKE CONCAT('%', :search, '%') OR " +
        "   l.entityType LIKE CONCAT('%', :search, '%') " +
        ")")
    Page<Logs> searchLogs(@Param("search") String search, Pageable pageable);
}
