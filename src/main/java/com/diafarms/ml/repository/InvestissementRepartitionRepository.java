package com.diafarms.ml.repository;

import com.diafarms.ml.models.InvestissementRepartition;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvestissementRepartitionRepository extends JpaRepository<InvestissementRepartition, Long> {
    
    // Trouver toutes les répartitions liées à un investissement spécifique
    List<InvestissementRepartition> findByInvestissementUniqueId(String uniqueId);

    // Requete complexe : Récupérer le coût d'amortissement total absorbé par un projet spécifique (Bande P-001)
    @Query("SELECT COALESCE(SUM(r.montantAlloue), 0.0) FROM InvestissementRepartition r WHERE r.projet.uniqueId = :projetUniqueId")
    Double getSommeAmortissementParProjet(@Param("projetUniqueId") String projetUniqueId);
}