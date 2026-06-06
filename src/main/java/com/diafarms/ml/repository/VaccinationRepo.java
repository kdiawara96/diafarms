package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.diafarms.ml.models.Vaccination;

public interface VaccinationRepo extends JpaRepository<Vaccination, Long> {
    
}
