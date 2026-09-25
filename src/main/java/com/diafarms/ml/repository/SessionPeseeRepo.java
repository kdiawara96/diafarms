package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.StatutSessionPesee;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.SessionPesee;

import jakarta.persistence.LockModeType;

@Repository
public interface SessionPeseeRepo extends JpaRepository<SessionPesee, Long> {

    Optional<SessionPesee> findByUniqueId(String uniqueId);

    // Scalaire (ne charge pas la session dans le contexte) : on verrouille le projet
    // AVANT de lire la session.
    @Query("SELECT s.projet.id FROM SessionPesee s WHERE s.uniqueId = :uniqueId AND s.farm.id = :farmId")
    Optional<Long> findProjetIdByUniqueIdAndFarm(@Param("uniqueId") String uniqueId, @Param("farmId") Long farmId);

    // Sérialise les synchronisations d'un même projet : deux envois simultanés de la même
    // session (renvoi après réponse perdue) ne doivent ni créer deux sessions ni
    // dupliquer des pesées.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM Projets p WHERE p.id = :id")
    Optional<Projets> lockProjet(@Param("id") Long id);

    // Pas de "(:param IS NULL OR ...)" (plantage Postgres) : hasStatut + statut factice non nul.
    @Query(value = "SELECT s FROM SessionPesee s JOIN s.projet p WHERE s.farm.id = :farmId " +
            "AND p.uniqueId = :projetUniqueId AND s.initialisation.removed = false " +
            "AND (:hasStatut = false OR s.statut = :statut) " +
            "ORDER BY CASE WHEN s.statut = com.diafarms.ml.enums.StatutSessionPesee.EN_COURS THEN 0 ELSE 1 END, " +
            "s.dateDebut DESC, s.id DESC",
            countQuery = "SELECT COUNT(s) FROM SessionPesee s JOIN s.projet p WHERE s.farm.id = :farmId " +
            "AND p.uniqueId = :projetUniqueId AND s.initialisation.removed = false " +
            "AND (:hasStatut = false OR s.statut = :statut)")
    Page<SessionPesee> search(@Param("farmId") Long farmId,
                              @Param("projetUniqueId") String projetUniqueId,
                              @Param("hasStatut") boolean hasStatut,
                              @Param("statut") StatutSessionPesee statut,
                              Pageable pageable);

    @Query("SELECT s FROM SessionPesee s WHERE s.projet.id = :projetId AND s.farm.id = :farmId " +
            "AND s.initialisation.removed = false " +
            "AND s.statut = com.diafarms.ml.enums.StatutSessionPesee.TERMINEE ORDER BY s.dateFin ASC, s.id ASC")
    List<SessionPesee> findTermineesByProjet(@Param("projetId") Long projetId, @Param("farmId") Long farmId);
}
