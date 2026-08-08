package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.MagasinVente;

@Repository
public interface MagasinVenteRepo extends JpaRepository<MagasinVente, Long> {

    Optional<MagasinVente> findByUniqueId(String uniqueId);

    @Query("SELECT m FROM MagasinVente m WHERE m.farm.id = :farmId AND m.initialisation.removed = false ORDER BY m.nom")
    List<MagasinVente> findAllActiveByFarm(@Param("farmId") Long farmId);

    // Magasins auxquels un vendeur précis est lié — périmètre de vente d'un VENTE pur
    // (voir VenteOeufsImpl/VenteReformeImpl), un ADMIN/RESPONSABLE voit tous les
    // magasins de la ferme sans restriction.
    @Query("SELECT DISTINCT m FROM MagasinVente m JOIN m.vendeurs v " +
        "WHERE m.farm.id = :farmId AND m.initialisation.removed = false AND v.uniqueId = :vendeurUniqueId ORDER BY m.nom")
    List<MagasinVente> findAssignedToVendeur(@Param("farmId") Long farmId, @Param("vendeurUniqueId") String vendeurUniqueId);
}
