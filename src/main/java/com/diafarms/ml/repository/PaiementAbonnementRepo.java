package com.diafarms.ml.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.enums.StatutPaiementAbonnement;
import com.diafarms.ml.models.PaiementAbonnement;

@Repository
public interface PaiementAbonnementRepo extends JpaRepository<PaiementAbonnement, Long> {

    Optional<PaiementAbonnement> findByUniqueId(String uniqueId);

    // Garde-fou "une seule déclaration en attente à la fois" pour une ferme donnée
    // (via son Abonnement) — voir AbonnementServiceImpl.declarerPaiement.
    Optional<PaiementAbonnement> findByAbonnement_IdAndStatut(Long abonnementId, StatutPaiementAbonnement statut);

    // Liste SUPER_ADMIN de toutes les déclarations en attente, toutes fermes
    // confondues — voir AbonnementServiceImpl.listEnAttente.
    @Query("SELECT p FROM PaiementAbonnement p WHERE p.statut = :statut ORDER BY p.dateDeclaration ASC")
    Page<PaiementAbonnement> findByStatutOrderByDateDeclarationAsc(@Param("statut") StatutPaiementAbonnement statut, Pageable pageable);
}
