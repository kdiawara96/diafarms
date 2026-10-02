package com.diafarms.ml.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.DTO.RepartitionRatioDTO;
import com.diafarms.ml.DTO.VenteRepartitionReelDTO;
import com.diafarms.ml.enums.TypeVenteOeufs;
import com.diafarms.ml.models.VenteOeufsRepartition;

@Repository
public interface VenteOeufsRepartitionRepo extends JpaRepository<VenteOeufsRepartition, Long> {

    Optional<VenteOeufsRepartition> findByUniqueId(String uniqueId);

    // Voir RepartitionRatioDTO / TransactionServiceImpl.enrichMontantReel — recherche
    // groupée (pas une par transaction) pour une liste paginée de transactions.
    // LEFT JOIN explicite sur client (nullable — vente directe) : un chemin implicite
    // (r.venteOeufs.client.nom) risquerait un INNER JOIN qui exclurait les ventes
    // sans client, voir le même raisonnement documenté sur CollecteOeufsRepo.search.
    @Query("SELECT new com.diafarms.ml.DTO.RepartitionRatioDTO(r.uniqueId, v.montant, v.montantRapporte, c.nom, v.uniqueId, dsp.fullName, c.id, 'VENTE_OEUFS') " +
        "FROM VenteOeufsRepartition r JOIN r.venteOeufs v LEFT JOIN v.client c LEFT JOIN v.demandeSuppressionPar dsp " +
        "WHERE r.uniqueId IN :uniqueIds")
    List<RepartitionRatioDTO> findRatiosByUniqueIds(@Param("uniqueIds") List<String> uniqueIds);

    // Voir VenteRepartitionReelDTO — sert à TransactionServiceImpl.getVentesReelParProjet
    // à corriger le théorique par projet au prorata réel/théorique de chaque vente.
    // Jointure explicite sur Transaction (via sourceUniqueId = r.uniqueId, voir
    // VenteOeufsImpl.repartirEtCreerTransactions) filtrée VALIDE : sans ça, une vente
    // créée par un VENTE pur (EN_ATTENTE tant qu'un ADMIN/RESPONSABLE ne l'a pas
    // validée) était comptée ici mais PAS dans les Transactions "valide" que le web
    // utilise pour le théorique de référence — le réel calculé dépassait alors le
    // théorique affiché, un résultat qui n'a pas de sens.
    @Query("SELECT new com.diafarms.ml.DTO.VenteRepartitionReelDTO(r.projet.uniqueId, r.projet.code, " +
        "r.montantAttribue, r.venteOeufs.montant, r.venteOeufs.montantRapporte) " +
        "FROM VenteOeufsRepartition r, Transaction t " +
        "WHERE t.sourceUniqueId = r.uniqueId AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE " +
        "AND r.projet.farm.id = :farmId AND r.venteOeufs.initialisation.removed = false " +
        // dateDebut/dateFin ATTENDUS NON-NULS — voir TransactionRepo.countByProjetIdsAndStatut.
        "AND r.venteOeufs.date >= :dateDebut AND r.venteOeufs.date <= :dateFin")
    List<VenteRepartitionReelDTO> findReelParProjet(@Param("farmId") Long farmId,
                                                      @Param("dateDebut") java.time.LocalDate dateDebut,
                                                      @Param("dateFin") java.time.LocalDate dateFin);

    List<VenteOeufsRepartition> findByVenteOeufs_UniqueId(String venteOeufsUniqueId);

    // Déjà vendu POUR CE PROJET (toutes ventes farm-wide confondues) — sert à
    // calculer le stock d'œufs restant du projet, part qu'il peut encore
    // contribuer à une prochaine vente (voir VenteOeufsImpl.stockDisponibleParProjet).
    @Query("SELECT COALESCE(SUM(r.quantiteAttribuee), 0) FROM VenteOeufsRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteOeufs.initialisation.removed = false")
    Integer sumQuantiteByProjetId(@Param("projetId") Long projetId);

    // Déjà vendu POUR CE PROJET, DEPUIS CE MAGASIN précis, POUR CE TYPE (BON/CASSE) —
    // voir VenteOeufsImpl.disponibleParProjetDansMagasin (stock magasin-scopé ET
    // type-scopé : une vente CASSE ne doit jamais réduire le disponible BON, et
    // inversement, remplace l'ancien calcul farm-wide de sumQuantiteByProjetId ci-dessus).
    @Query("SELECT COALESCE(SUM(r.quantiteAttribuee), 0) FROM VenteOeufsRepartition r " +
        "WHERE r.projet.id = :projetId AND r.venteOeufs.magasin.id = :magasinId " +
        "AND r.venteOeufs.typeOeuf = :typeOeuf AND r.venteOeufs.initialisation.removed = false")
    Integer sumQuantiteByProjetIdAndMagasinId(@Param("projetId") Long projetId, @Param("magasinId") Long magasinId,
                                               @Param("typeOeuf") TypeVenteOeufs typeOeuf);

    // Total vendu DEPUIS ce magasin, tous projets contributeurs confondus, POUR CE TYPE —
    // pour l'aperçu global de stock du magasin (StockMagasinDTO).
    @Query("SELECT COALESCE(SUM(r.quantiteAttribuee), 0) FROM VenteOeufsRepartition r " +
        "WHERE r.venteOeufs.magasin.id = :magasinId AND r.venteOeufs.typeOeuf = :typeOeuf " +
        "AND r.venteOeufs.initialisation.removed = false")
    Integer sumQuantiteByMagasinId(@Param("magasinId") Long magasinId, @Param("typeOeuf") TypeVenteOeufs typeOeuf);

    @Query("SELECT r FROM VenteOeufsRepartition r JOIN FETCH r.projet WHERE r.venteOeufs.id IN :venteIds")
    List<VenteOeufsRepartition> findByVenteIds(@Param("venteIds") List<Long> venteIds);

    // Encaissement par projet (voir EncaissementProjetService) : parts du projet dans ses
    // ventes actives. [venteUniqueId, venteMontant, montantAttribue, clientId (null = vente
    // sans client), venteMontantRapporte, venteQuantite, quantiteAttribuee]. LEFT JOIN
    // explicite sur le client : une jointure implicite écarterait les ventes sans client.
    @Query("SELECT v.uniqueId, v.montant, r.montantAttribue, c.id, v.montantRapporte, v.quantiteOeufs, r.quantiteAttribuee, (SELECT SUM(r2.montantAttribue) FROM VenteOeufsRepartition r2 WHERE r2.venteOeufs = v) " +
        "FROM VenteOeufsRepartition r JOIN r.venteOeufs v LEFT JOIN v.client c " +
        "WHERE r.projet.id = :projetId AND v.initialisation.removed = false")
    List<Object[]> findPartsActivesParProjet(@Param("projetId") Long projetId);

    // Parts par projet de plusieurs ventes (popup "à quels Projets a servi ce paiement").
    // [venteUniqueId, venteMontant, projetUniqueId, projetCode, projetTitre, montantAttribue,
    // venteQuantite, quantiteAttribuee], dans l'ordre des lignes de répartition.
    @Query("SELECT v.uniqueId, v.montant, p.uniqueId, p.code, p.titre, r.montantAttribue, v.quantiteOeufs, r.quantiteAttribuee, (SELECT SUM(r2.montantAttribue) FROM VenteOeufsRepartition r2 WHERE r2.venteOeufs = v) " +
        "FROM VenteOeufsRepartition r JOIN r.venteOeufs v JOIN r.projet p WHERE v.uniqueId IN :uids ORDER BY r.id")
    List<Object[]> findPartsParVentes(@Param("uids") java.util.Collection<String> uids);
}
