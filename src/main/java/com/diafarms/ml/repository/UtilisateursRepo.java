package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    // Trouver tous les utilisateurs archivés
    Page<Utilisateurs> findByInitialisationArchiveTrue(Pageable pageable);

    // Trouver tous les utilisateurs actifs (non archivés)
    Page<Utilisateurs> findByInitialisationArchiveFalse(Pageable pageable);



}
