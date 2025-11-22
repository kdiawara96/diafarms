package com.diafarms.ml.repository;

import java.time.LocalDateTime;
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
    // List<Logs> findLogsByDeletedFalse();
    // Optional<Logs> findByUniqueIdAndDeletedFalse(String unique);
    Page<Logs> findAllByIdActionAndDeletedFalse(Pageable pageable, Long idAction);
    Page<Logs> findAllByClassNameAndDeletedFalse(Pageable pageable, String nomClass);
    Optional<Logs> findByUniqueId(String uniqueId);
    Page<Logs> findAllByClassNameAndDeletedTrue(Pageable pageable, String nomClass);
    Page<Logs> findAllByIdActionAndDeletedTrue(Pageable pageable, Long idAction);
    Page<Logs> findAllByDeletedFalse(Pageable pageable);
    List<Logs> findAllByDateCreationBetween(LocalDateTime start, LocalDateTime end);

    Page<Logs> findAllByDeletedTrue(Pageable pageable);


    @Query(value = "SELECT * FROM logs l " +
        "WHERE (:searchTerm IS NULL " +
        "OR LOWER(l.class_name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "OR LOWER(l.email_actionnaire) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
        "OR LOWER(l.action) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
       nativeQuery = true)
    List<Logs> searchLogs(@Param("searchTerm") String searchTerm);

}
