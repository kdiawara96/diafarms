package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.SoldeVendeur;

@Repository
public interface SoldeVendeurRepo extends JpaRepository<SoldeVendeur, Long> {

    Optional<SoldeVendeur> findByVendeur_Id(Long vendeurId);

    @Query("SELECT s FROM SoldeVendeur s WHERE s.vendeur.uniqueId = :vendeurUniqueId AND s.farm.id = :farmId")
    Optional<SoldeVendeur> findByVendeurUniqueIdAndFarmId(@Param("vendeurUniqueId") String vendeurUniqueId, @Param("farmId") Long farmId);

    // Vue d'ensemble admin/responsable : tous les soldes vendeur non nuls de la ferme,
    // pour repérer d'un coup d'œil qui doit de l'argent — voir SoldeVendeurController.
    @Query("SELECT s FROM SoldeVendeur s WHERE s.farm.id = :farmId AND s.solde <> 0.0 ORDER BY s.solde DESC")
    List<SoldeVendeur> findAllNonZeroByFarmId(@Param("farmId") Long farmId);
}
