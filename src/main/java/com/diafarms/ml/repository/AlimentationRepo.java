package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Alimentation;

@Repository
public interface AlimentationRepo extends JpaRepository<Alimentation, Long> {
    
}
