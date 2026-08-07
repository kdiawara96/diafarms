package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.FarmAppSettings;

@Repository
public interface FarmAppSettingsRepo extends JpaRepository<FarmAppSettings, Long> {
    Optional<FarmAppSettings> findByFarm_Id(Long farmId);
}
