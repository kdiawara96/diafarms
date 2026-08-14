package com.diafarms.ml.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.SalaireHistorique;

@Repository
public interface SalaireHistoriqueRepo extends JpaRepository<SalaireHistorique, Long> {

    // Le taux "courant" (toujours en vigueur) d'un Salaire, s'il en a un — fermé par
    // SalaireServiceImpl.definir() dès que le taux change réellement.
    SalaireHistorique findFirstBySalaire_IdAndDateFinIsNull(Long salaireId);

    // Le taux qui était en vigueur À une date donnée (dernier dateEffective <= limite)
    // — voir SalaireServiceImpl.resolveTauxPourPeriode, utilisé par payer() pour ne
    // jamais proposer par erreur le taux ACTUEL sur une période antérieure à un
    // changement de grille.
    SalaireHistorique findFirstBySalaire_IdAndDateEffectiveLessThanEqualOrderByDateEffectiveDesc(Long salaireId, LocalDate limite);
}
