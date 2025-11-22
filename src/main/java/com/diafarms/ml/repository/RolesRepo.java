package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.diafarms.ml.models.Roles;


public interface RolesRepo extends JpaRepository<Roles, Long> {

    Roles findByRole(String role);
    Optional<Roles> findByUniqueId(String uniqueId);
    Roles findByUniqueIdAndInitialisationRemovedFalseAndInitialisationArchiveFalse(String uniqueId);
    Roles findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(String roleUserAdmin);
    Page<Roles> findByInitialisationRemovedFalseAndInitialisationArchiveFalse(Pageable pageable);
    List<Roles> findAllByInitialisationRemovedFalseAndInitialisationArchiveFalse();
    Page<Roles> findByInitialisationRemovedFalseAndInitialisationArchiveTrue(Pageable pageable);

    Page<Roles> findByInitialisationRemovedTrue(Pageable pageable);

    // 🔍 Nouvelle méthode de recherche par nom
    @Query(value = "SELECT * FROM roles r WHERE " +
            "(:searchTerm IS NULL OR LOWER(r.role) LIKE LOWER(CONCAT('%', :searchTerm, '%')))",
            nativeQuery = true)
    List<Roles> searchRoles(@Param("searchTerm") String searchTerm);
}