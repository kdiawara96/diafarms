package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Utilisateurs;

@Repository
public interface UtilisateursRepo extends JpaRepository<Utilisateurs, Long>  {
    
    Optional<Utilisateurs> findByUniqueId(String uniqueId);
    Utilisateurs findByEmail(String email);
    Utilisateurs findByTelephone(String email);
    Utilisateurs findByUsername(String username);
    Optional<Utilisateurs> findByUsernameOrEmailOrTelephone(String username, String email, String telephone);
    boolean existsByEmail(String email);
    boolean existsByTelephone(String telephone);
    boolean existsByUsername(String username);
    Optional<Utilisateurs> findByUniqueIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(String uniqueId);
    Utilisateurs findByEmailAndInitialisationRemovedFalseAndInitialisationArchiveFalse(String username);

    Optional<Utilisateurs> findByEmailOrUsernameOrTelephoneAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
        String email, String username, String telephone);


    // Trouver tous les utilisateurs archivés
    Page<Utilisateurs> findByInitialisationArchiveTrue(Pageable pageable);

    // Trouver tous les utilisateurs actifs (non archivés)
    Page<Utilisateurs> findByInitialisationArchiveFalse(Pageable pageable);

    @Query("SELECT u FROM Utilisateurs u " +
        "WHERE u.initialisation.removed = false " +
        "AND u.initialisation.archive = false " +
        "AND (" +
        "   LOWER(u.fullName) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
        "   LOWER(u.username) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
        "   LOWER(u.email) LIKE LOWER(CONCAT('%', :search, '%')) OR " +
        "   LOWER(u.telephone) LIKE LOWER(CONCAT('%', :search, '%')) " +
        ") " +
        "ORDER BY u.initialisation.createdAt DESC")
    List<Utilisateurs> searchUsers(@Param("search") String search);




}
