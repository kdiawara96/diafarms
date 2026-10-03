package com.diafarms.ml.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import com.diafarms.ml.models.Transaction;

// Lectures groupées de la page Reporting (voir ReportingService) : quelques requêtes
// agrégées par jour et par projet pour une ferme et une période, jamais une ligne par
// saisie renvoyée au navigateur. Bornes de dates toujours concrètes (jamais null) : pas
// d'idiome "(:x IS NULL OR ...)", qui plante sur Postgres.
public interface ReportingRepo extends Repository<Transaction, Long> {

    // Transactions VALIDES non supprimées de la période :
    // [date, projetId (null = commune), type, sourceType, categorie, siteId, Σ montant].
    @Query("SELECT t.date, p.id, t.type, t.sourceType, t.categorie, s.id, SUM(t.montant) FROM Transaction t " +
           "LEFT JOIN t.projet p LEFT JOIN t.site s WHERE t.farm.id = :farmId " +
           "AND t.initialisation.removed = false AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE " +
           "AND t.date >= :deb AND t.date <= :fin GROUP BY t.date, p.id, t.type, t.sourceType, t.categorie, s.id")
    List<Object[]> transactionsParJour(@Param("farmId") Long farmId, @Param("deb") LocalDate deb, @Param("fin") LocalDate fin);

    // Lignes de vente d'œufs de la période (une transaction VALIDE par projet contributeur,
    // mêmes filtres que VenteOeufsRepo.sumRapporteSansClient) :
    // [projetId, date, montant de la ligne, venteUniqueId, clientId, venteMontant,
    //  venteMontantRapporte, montantAttribue, Σ montants attribués de la vente,
    //  vendeurUniqueId, vendeurNom].
    @Query("SELECT p.id, t.date, t.montant, v.uniqueId, c.id, v.montant, v.montantRapporte, r.montantAttribue, " +
           "(SELECT SUM(r2.montantAttribue) FROM VenteOeufsRepartition r2 WHERE r2.venteOeufs = v), u.uniqueId, u.fullName " +
           "FROM Transaction t LEFT JOIN t.creePar u, VenteOeufsRepartition r JOIN r.venteOeufs v LEFT JOIN v.client c JOIN r.projet p " +
           "WHERE t.sourceUniqueId = r.uniqueId AND t.sourceType = com.diafarms.ml.enums.SourceTransaction.VENTE_OEUFS " +
           "AND t.farm.id = :farmId AND t.initialisation.removed = false " +
           "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE " +
           "AND t.type = com.diafarms.ml.enums.TypeTransaction.ENTREE AND v.initialisation.removed = false " +
           "AND t.date >= :deb AND t.date <= :fin")
    List<Object[]> lignesVenteOeufs(@Param("farmId") Long farmId, @Param("deb") LocalDate deb, @Param("fin") LocalDate fin);

    // Même chose pour les ventes de réforme.
    @Query("SELECT p.id, t.date, t.montant, v.uniqueId, c.id, v.montant, v.montantRapporte, r.montantAttribue, " +
           "(SELECT SUM(r2.montantAttribue) FROM VenteReformeRepartition r2 WHERE r2.venteReforme = v), u.uniqueId, u.fullName " +
           "FROM Transaction t LEFT JOIN t.creePar u, VenteReformeRepartition r JOIN r.venteReforme v LEFT JOIN v.client c JOIN r.projet p " +
           "WHERE t.sourceUniqueId = r.uniqueId AND t.sourceType = com.diafarms.ml.enums.SourceTransaction.VENTE_REFORME " +
           "AND t.farm.id = :farmId AND t.initialisation.removed = false " +
           "AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE " +
           "AND t.type = com.diafarms.ml.enums.TypeTransaction.ENTREE AND v.initialisation.removed = false " +
           "AND t.date >= :deb AND t.date <= :fin")
    List<Object[]> lignesVenteReforme(@Param("farmId") Long farmId, @Param("deb") LocalDate deb, @Param("fin") LocalDate fin);

    // Ventes diverses (fientes, autres) de la période, par vendeur :
    // [projetId, vendeurUniqueId, vendeurNom, nombre, Σ montant].
    @Query("SELECT p.id, u.uniqueId, u.fullName, COUNT(t), SUM(t.montant) FROM Transaction t " +
           "LEFT JOIN t.projet p LEFT JOIN t.creePar u WHERE t.farm.id = :farmId " +
           "AND t.sourceType = com.diafarms.ml.enums.SourceTransaction.VENTE_DIVERSE " +
           "AND t.initialisation.removed = false AND t.statut = com.diafarms.ml.enums.StatutTransaction.VALIDE " +
           "AND t.type = com.diafarms.ml.enums.TypeTransaction.ENTREE " +
           "AND t.date >= :deb AND t.date <= :fin GROUP BY p.id, u.uniqueId, u.fullName")
    List<Object[]> ventesDiversesParVendeur(@Param("farmId") Long farmId, @Param("deb") LocalDate deb, @Param("fin") LocalDate fin);

    // Argent des clients reçu dans la période et déjà imputé sur une vente (date du
    // paiement) : [datePaiement, cibleType, venteUniqueId, Σ imputé].
    @Query("SELECT pc.date, i.cibleType, i.cibleUniqueId, SUM(i.montant) FROM ImputationPaiement i JOIN i.paiement pc " +
           "WHERE i.farm.id = :farmId AND i.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND pc.statut = com.diafarms.ml.enums.StatutMouvement.ACTIF " +
           "AND i.cibleType IN (com.diafarms.ml.enums.CibleImputation.VENTE_OEUFS, com.diafarms.ml.enums.CibleImputation.VENTE_REFORME) " +
           "AND pc.date >= :deb AND pc.date <= :fin GROUP BY pc.date, i.cibleType, i.cibleUniqueId")
    List<Object[]> imputationsParJour(@Param("farmId") Long farmId, @Param("deb") LocalDate deb, @Param("fin") LocalDate fin);

    // Œufs : [projetId, date, Σ collectés, Σ cassés].
    @Query("SELECT c.projet.id, c.date, SUM(c.oeufsCollectes), SUM(COALESCE(c.oeufsCasses, 0)) FROM CollecteOeufs c " +
           "WHERE c.projet.farm.id = :farmId AND c.initialisation.removed = false " +
           "AND c.date >= :deb AND c.date <= :fin GROUP BY c.projet.id, c.date")
    List<Object[]> collectesParJour(@Param("farmId") Long farmId, @Param("deb") LocalDate deb, @Param("fin") LocalDate fin);

    // Mortalité jusqu'à une date (historique compris, pour l'effectif) : [projetId, date, Σ morts].
    @Query("SELECT m.projet.id, m.date, SUM(m.nombreMorts) FROM Mortalite m WHERE m.projet.farm.id = :farmId " +
           "AND m.initialisation.removed = false AND m.date <= :fin GROUP BY m.projet.id, m.date")
    List<Object[]> mortsParJourJusqua(@Param("farmId") Long farmId, @Param("fin") LocalDate fin);

    // Sujets réformés jusqu'à une date : [projetId, date, Σ sujets].
    @Query("SELECT r.projet.id, r.date, SUM(r.nombreSujets) FROM Reforme r WHERE r.projet.farm.id = :farmId " +
           "AND r.initialisation.removed = false AND r.date <= :fin GROUP BY r.projet.id, r.date")
    List<Object[]> reformesParJourJusqua(@Param("farmId") Long farmId, @Param("fin") LocalDate fin);

    // Aliment consommé : [projetId, date, Σ kg].
    @Query("SELECT c.projet.id, c.date, SUM(c.quantiteKg) FROM ConsommationAliment c WHERE c.projet.farm.id = :farmId " +
           "AND c.initialisation.removed = false AND c.date >= :deb AND c.date <= :fin GROUP BY c.projet.id, c.date")
    List<Object[]> alimentParJour(@Param("farmId") Long farmId, @Param("deb") LocalDate deb, @Param("fin") LocalDate fin);
}
