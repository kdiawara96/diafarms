package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.SessionPeseeEvenement;

@Repository
public interface SessionPeseeEvenementRepo extends JpaRepository<SessionPeseeEvenement, Long> {

    @Query("SELECT e FROM SessionPeseeEvenement e LEFT JOIN FETCH e.par WHERE e.session.id = :sessionId " +
            "ORDER BY e.date ASC, e.id ASC")
    List<SessionPeseeEvenement> findBySessionIdOrdered(@Param("sessionId") Long sessionId);
}
