package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Abonnement;

@Repository
public interface AbonnementRepo extends JpaRepository<Abonnement, Long> {

    Optional<Abonnement> findByFarm_Id(Long farmId);

    Optional<Abonnement> findByUniqueId(String uniqueId);

    // Tous les abonnements avec leur ferme (tâche des rappels, portail SUPER_ADMIN) :
    // volume = nombre de fermes, trivial.
    @Query("SELECT a FROM Abonnement a JOIN FETCH a.farm ORDER BY a.dateFin ASC")
    List<Abonnement> findAllAvecFerme();
}
