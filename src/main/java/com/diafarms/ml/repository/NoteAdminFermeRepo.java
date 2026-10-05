package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.NoteAdminFerme;

@Repository
public interface NoteAdminFermeRepo extends JpaRepository<NoteAdminFerme, Long> {

    List<NoteAdminFerme> findByFarm_IdOrderByCreeLeDesc(Long farmId);
}
