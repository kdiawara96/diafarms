package com.diafarms.ml.repository;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.TypeStockMagasin;
import com.diafarms.ml.models.MagasinTransfert;

@Repository
public interface MagasinTransfertRepo extends JpaRepository<MagasinTransfert, Long> {

    @Query("SELECT t FROM MagasinTransfert t WHERE t.magasin.id = :magasinId AND t.initialisation.removed = false " +
        "ORDER BY t.date DESC")
    Page<MagasinTransfert> searchByMagasin(@Param("magasinId") Long magasinId, Pageable pageable);

    // Déjà transféré depuis CE projet (tous magasins confondus) — plafonne un nouveau
    // transfert : on ne peut pas transférer plus que le stock du projet pas encore
    // envoyé nulle part (voir MagasinTransfertServiceImpl.disponibleATransfererDepuisProjet).
    @Query("SELECT COALESCE(SUM(t.quantite), 0) FROM MagasinTransfert t " +
        "WHERE t.projet.id = :projetId AND t.type = :type AND t.initialisation.removed = false")
    Integer sumQuantiteByProjetIdAndType(@Param("projetId") Long projetId, @Param("type") TypeStockMagasin type);

    // Reçu par CE magasin, pour CE projet précis — sert de base "disponible" par
    // projet pour la répartition d'une vente à l'intérieur du magasin, voir
    // VenteOeufsImpl/VenteReformeImpl.disponibleParProjetDansMagasin.
    @Query("SELECT COALESCE(SUM(t.quantite), 0) FROM MagasinTransfert t " +
        "WHERE t.magasin.id = :magasinId AND t.projet.id = :projetId AND t.type = :type " +
        "AND t.initialisation.removed = false")
    Integer sumQuantiteByMagasinAndProjetAndType(@Param("magasinId") Long magasinId, @Param("projetId") Long projetId,
                                                  @Param("type") TypeStockMagasin type);

    // Liste des projets ayant déjà transféré vers ce magasin pour ce type — évite de
    // boucler sur TOUS les projets de la ferme pour calculer le disponible par projet.
    @Query("SELECT DISTINCT t.projet.id FROM MagasinTransfert t " +
        "WHERE t.magasin.id = :magasinId AND t.type = :type AND t.initialisation.removed = false")
    List<Long> findDistinctProjetIdsByMagasinAndType(@Param("magasinId") Long magasinId, @Param("type") TypeStockMagasin type);

    // Total reçu par ce magasin, tous projets confondus — pour l'aperçu global de
    // stock du magasin (StockMagasinDTO), pas la répartition par projet.
    @Query("SELECT COALESCE(SUM(t.quantite), 0) FROM MagasinTransfert t " +
        "WHERE t.magasin.id = :magasinId AND t.type = :type AND t.initialisation.removed = false")
    Integer sumQuantiteByMagasinIdAndType(@Param("magasinId") Long magasinId, @Param("type") TypeStockMagasin type);
}
