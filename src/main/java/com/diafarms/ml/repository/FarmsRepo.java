package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Farm;

@Repository
public interface FarmsRepo extends JpaRepository<Farm, Long> {
    
    Farm findByUniqueId(String uniqueId);

}
