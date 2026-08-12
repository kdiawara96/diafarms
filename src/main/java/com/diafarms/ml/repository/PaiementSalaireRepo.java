package com.diafarms.ml.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.PaiementSalaire;

@Repository
public interface PaiementSalaireRepo extends JpaRepository<PaiementSalaire, Long> {

    boolean existsBySalaire_IdAndPeriode(Long salaireId, String periode);

    // Le plus récent d'abord — utilisé pour afficher "dernier paiement" sur SalaireDTO.
    PaiementSalaire findFirstBySalaire_IdOrderByPeriodeDesc(Long salaireId);

    // Pas d'ORDER BY ici : le tri vient du Pageable (Sort.by("periode") côté service),
    // comme CommandeRepo/FactureRepo.search — un ORDER BY explicite en plus provoquerait
    // un conflit.
    @Query("SELECT p FROM PaiementSalaire p WHERE p.salaire.id = :salaireId AND p.initialisation.removed = false")
    Page<PaiementSalaire> findBySalaireId(@Param("salaireId") Long salaireId, Pageable pageable);
}
