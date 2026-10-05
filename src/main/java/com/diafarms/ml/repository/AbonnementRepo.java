package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
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

    // Verrou d'écriture (SELECT ... FOR UPDATE) : deux actions sur le même abonnement
    // (validation d'un paiement, actions de la console SUPER_ADMIN) s'exécutent l'une après
    // l'autre et relisent l'état à jour, sans perdre une prolongation ni annuler une
    // suspension. À appeler AVANT toute autre lecture de l'abonnement dans la transaction.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Abonnement a WHERE a.farm.id = :farmId")
    Optional<Abonnement> verrouillerParFerme(@Param("farmId") Long farmId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Abonnement a WHERE a.id = :id")
    Optional<Abonnement> verrouillerParId(@Param("id") Long id);
}
