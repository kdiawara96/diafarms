package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.diafarms.ml.models.OccupationBatiment;

public interface OccupationBatimentRepo extends JpaRepository<OccupationBatiment, Long> {
    
}
