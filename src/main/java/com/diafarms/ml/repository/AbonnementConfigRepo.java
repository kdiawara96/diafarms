package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.AbonnementConfig;

@Repository
public interface AbonnementConfigRepo extends JpaRepository<AbonnementConfig, Long> {

    // Ligne unique de config (voir AbonnementServiceImpl.getOuCreerConfig) : pas de
    // contrainte d'unicité sur cette table, donc une lecture ordonnée par id est
    // nécessaire pour garantir que tous les appelants lisent la même ligne (au lieu
    // d'un findAll().stream().findFirst() dont l'ordre n'est pas garanti par Postgres).
    AbonnementConfig findFirstByOrderByIdAsc();
}
