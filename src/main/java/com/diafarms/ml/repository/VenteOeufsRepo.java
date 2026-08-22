package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.VenteOeufs;

@Repository
public interface VenteOeufsRepo extends JpaRepository<VenteOeufs, Long> {

    Optional<VenteOeufs> findByUniqueId(String uniqueId);

    // Historique des ventes d'œufs d'un client précis — voir ClientServiceImpl.getReport.
    @Query("SELECT v FROM VenteOeufs v WHERE v.client.uniqueId = :clientUniqueId AND v.farm.id = :farmId " +
        "AND v.initialisation.removed = false ORDER BY v.date DESC")
    List<VenteOeufs> findByClientUniqueIdAndFarmId(@Param("clientUniqueId") String clientUniqueId, @Param("farmId") Long farmId);

    @Query("SELECT v FROM VenteOeufs v WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Page<VenteOeufs> search(@Param("farmId") Long farmId, Pageable pageable);

    @Query("SELECT COALESCE(SUM(v.quantiteOeufs), 0) FROM VenteOeufs v " +
        "WHERE v.farm.id = :farmId AND v.initialisation.removed = false")
    Integer sumQuantiteByFarmId(@Param("farmId") Long farmId);

    // Montant réellement rapporté par les vendeurs — voir TransactionServiceImpl.
    // getStats, sert à corriger "Total entrées" qui surestimait le cash réellement en
    // caisse en sommant le montant théorique des ventes plutôt que ce qui a vraiment
    // été rapporté. montantRapporte est OPTIONNEL à la saisie (renseigné seulement
    // quand le vendeur signale un écart, voir CreateVenteOeufsDialog côté web) : une
    // vente sans montantRapporte n'a PAS de dette connue, donc elle compte pour son
    // montant théorique complet (COALESCE(montantRapporte, montant)) — un simple
    // SUM(montantRapporte) ignorait silencieusement (SQL) toutes les ventes où ce
    // champ n'a jamais été rempli, écrasant "Montant reçu" bien en dessous de la
    // réalité au lieu de ne compter que les vraies dettes en cours.
    // dateDebut/dateFin ATTENDUS NON-NULS — voir TransactionRepo.countByProjetIdsAndStatut
    // pour le raisonnement (le pattern "IS NULL OR" plantait Postgres sur ce type de
    // requête agrégat, quelle que soit la valeur réelle passée).
    @Query("SELECT COALESCE(SUM(COALESCE(v.montantRapporte, v.montant)), 0) FROM VenteOeufs v " +
        "WHERE v.farm.id = :farmId AND v.initialisation.removed = false " +
        "AND v.date >= :dateDebut AND v.date <= :dateFin")
    Double sumMontantRapporteByFarmIdAndDateRange(@Param("farmId") Long farmId,
                                                   @Param("dateDebut") java.time.LocalDate dateDebut,
                                                   @Param("dateFin") java.time.LocalDate dateFin);

    // Ventes d'œufs BONS (jamais cassés) de toute la ferme, triées par date croissante —
    // sert à RapportJournalierServiceImpl à déterminer le "dernier prix de vente connu"
    // (PUA/potentiel) jour par jour : la vente n'est pas rattachée à un seul projet
    // (répartie entre contributeurs), donc le prix reste un indicatif farm-wide, pas
    // projet-par-projet.
    @Query("SELECT v FROM VenteOeufs v WHERE v.farm.id = :farmId " +
        "AND v.typeOeuf = com.diafarms.ml.enums.TypeVenteOeufs.BON AND v.initialisation.removed = false " +
        "ORDER BY v.date ASC")
    java.util.List<VenteOeufs> findAllBonByFarmIdOrderByDateAsc(@Param("farmId") Long farmId);
}
