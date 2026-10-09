package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.MouvementCredit;

@Repository
public interface MouvementCreditRepo extends JpaRepository<MouvementCredit, Long> {

    boolean existsByCle(String cle);

    // Compte de crédit d'une ferme, plus récent d'abord, avec l'auteur (volume : une
    // mensualité par mois plus les recharges, quelques dizaines de lignes par an).
    @Query("SELECT m FROM MouvementCredit m LEFT JOIN FETCH m.auteur WHERE m.abonnement.id = :abonnementId "
            + "ORDER BY m.dateMouvement DESC, m.id DESC")
    List<MouvementCredit> findByAbonnement(@Param("abonnementId") Long abonnementId);
}
