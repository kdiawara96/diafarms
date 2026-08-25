package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.AbonnementConfig;

@Repository
public interface AbonnementConfigRepo extends JpaRepository<AbonnementConfig, Long> {
}
