package com.diafarms.ml.repository;

import java.util.List;
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

    // Historique complet des déclarations de paiement d'une ferme (voir
    // AbonnementServiceImpl.getHistorique) — volume trivial (au plus quelques
    // dizaines de lignes sur toute la vie d'un abonnement), filtrage/pagination
    // faits côté service en Java plutôt qu'en JPQL avec paramètre optionnel
    // (évite le bug Postgres "(:param IS NULL OR ...)" sur un type énuméré).
    List<PaiementAbonnement> findByAbonnement_Farm_IdOrderByDateDeclarationDesc(Long farmId);

    // Toutes les déclarations d'un statut, avec abonnement, ferme et auteurs chargés en
    // une requête (portail SUPER_ADMIN : évite une requête par ferme).
    @Query("SELECT p FROM PaiementAbonnement p JOIN FETCH p.abonnement a JOIN FETCH a.farm "
            + "LEFT JOIN FETCH p.declarePar LEFT JOIN FETCH p.validePar WHERE p.statut = :statut ORDER BY p.dateDeclaration ASC")
    List<PaiementAbonnement> findAllAvecFermeParStatut(@Param("statut") StatutPaiementAbonnement statut);

    // Paiements d'une liste d'identifiants, tout chargé (console SUPER_ADMIN, liste filtrée).
    @Query("SELECT p FROM PaiementAbonnement p JOIN FETCH p.abonnement a JOIN FETCH a.farm "
            + "LEFT JOIN FETCH p.declarePar LEFT JOIN FETCH p.validePar WHERE p.id IN :ids")
    List<PaiementAbonnement> findAllAvecFermeParIds(@Param("ids") java.util.Collection<Long> ids);
}
