package com.diafarms.ml.repository;

import com.diafarms.ml.models.FichierMedia;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FichierMediaRepository extends JpaRepository<FichierMedia, Long> {
    List<FichierMedia> findByProjetUniqueId(String projetUniqueId);
    List<FichierMedia> findByFarmId(Long farmId);
}