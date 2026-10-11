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

    // Répartitions encore actives (non clôturées) pour un projet donné
    @Query("SELECT r FROM InvestissementRepartition r WHERE r.projet.uniqueId = :projetUniqueId AND r.dateFin IS NULL")
    List<InvestissementRepartition> findActiveByProjetUniqueId(@Param("projetUniqueId") String projetUniqueId);

    // Coût d'amortissement absorbé par un projet (fiche Projet "Résultat complet", rapport
    // PDF du projet). Un investissement DÉDIÉ a déjà sa dépense entière (transaction SORTIE,
    // sourceUniqueId = uniqueId de l'investissement, voir InvestissementServiceImpl.
    // syncTransaction) dans les dépenses de ce projet : sa part d'amortissement n'est donc pas
    // ajoutée une deuxième fois. Règle : on écarte la répartition dès qu'une dépense validée
    // et non supprimée de cet investissement est rattachée à ce même projet. Un investissement
    // commun (dépense de ferme, sans projet) garde sa part comme avant.
    @Query("SELECT COALESCE(SUM(r.montantAlloue), 0.0) FROM InvestissementRepartition r WHERE r.projet.uniqueId = :projetUniqueId "
            + "AND NOT EXISTS (SELECT t.id FROM Transaction t WHERE t.sourceUniqueId = r.investissement.uniqueId "
            + "AND t.projet.id = r.projet.id "
            + "AND t.type = com.diafarms.ml.enums.TypeTransaction.SORTIE "
            + "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE "
            + "AND (t.initialisation.removed IS NULL OR t.initialisation.removed = false))")
    Double getSommeAmortissementParProjet(@Param("projetUniqueId") String projetUniqueId);

    // Pour vérifier proprement l'existence avant suppression
    boolean existsById(Long id);
}