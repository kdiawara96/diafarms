package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Abonnement;

@Repository
public interface AbonnementRepo extends JpaRepository<Abonnement, Long> {

    Optional<Abonnement> findByFarm_Id(Long farmId);

    Optional<Abonnement> findByUniqueId(String uniqueId);
}
