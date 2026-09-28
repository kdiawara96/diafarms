package com.diafarms.ml.repository;

import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Investissement;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvestissementRepository extends JpaRepository<Investissement, Long> {
    
    Optional<Investissement> findByUniqueId(String uniqueId);
    
    // Récupérer les investissements actifs d'une ferme
    // List<Investissement> findByFarmIdAndInitialisationRemovedFalse(Long farmId);
    
    Page<Investissement> findByFarmIdAndInitialisationRemovedFalse(Long farmId, Pageable pageable);
    
    List<Investissement> findByFarm(Farm farm);

    // Requête custom pour recalculer la somme de la VNC (Valeur Nette) globale de la ferme
    @Query("SELECT SUM(i.montant - i.amortiCumule) FROM Investissement i WHERE i.farm.id = :farmId AND i.initialisation.removed = false")
    Double getValeurNetteGlobale(@Param("farmId") Long farmId);

    // Liens investissement -> poulailler (badge de la page Poulaillers).
    @Query("SELECT b.id, i.nom FROM Investissement i JOIN i.batiments b WHERE b.id IN :ids ORDER BY i.dateAchat")
    List<Object[]> nomsParBatiments(@Param("ids") Collection<Long> ids);

    // Suppression d'un poulailler : retire uniquement ses lignes de liaison.
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM investissement_batiments WHERE batiment_id = :batimentId", nativeQuery = true)
    int supprimerLiensBatiment(@Param("batimentId") Long batimentId);
}