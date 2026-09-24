package com.diafarms.ml.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Pesee;

@Repository
public interface PeseeRepo extends JpaRepository<Pesee, Long> {

    @Query("SELECT p FROM Pesee p WHERE p.session.id = :sessionId ORDER BY p.dateHeure ASC, p.id ASC")
    List<Pesee> findBySessionIdOrdered(@Param("sessionId") Long sessionId);

    // Pour détecter un uniqueId de pesée déjà utilisé dans une AUTRE session.
    @Query("SELECT p FROM Pesee p JOIN FETCH p.session WHERE p.uniqueId IN :uniqueIds")
    List<Pesee> findByUniqueIdIn(@Param("uniqueIds") Collection<String> uniqueIds);

    // [sessionId, nombre de pesées non annulées] pour la liste.
    @Query("SELECT p.session.id, COUNT(p) FROM Pesee p WHERE p.session.id IN :sessionIds " +
            "AND p.annulee = false GROUP BY p.session.id")
    List<Object[]> countActivesBySessionIds(@Param("sessionIds") Collection<Long> sessionIds);
}
