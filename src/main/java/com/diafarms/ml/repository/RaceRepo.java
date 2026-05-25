package com.diafarms.ml.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.models.Logs;
import com.diafarms.ml.models.Race;

@Repository
public interface RaceRepo extends JpaRepository<com.diafarms.ml.models.Race, Long> {
	Race findByUniqueId(String uniqueId);
}
