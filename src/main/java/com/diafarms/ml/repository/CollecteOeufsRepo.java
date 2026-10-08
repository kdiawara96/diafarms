package com.diafarms.ml.repository;

import java.time.LocalDate;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.diafarms.ml.models.CollecteOeufs;

@Repository
public interface CollecteOeufsRepo extends JpaRepository<CollecteOeufs, Long> {

    Optional<CollecteOeufs> findByUniqueId(String uniqueId);

    // LEFT JOIN explicite sur projet/batiment : un chemin implicite dans le WHERE
    // forcerait un INNER JOIN et ferait disparaître les lignes à FK bâtiment nulle.
    // hasX = booléens toujours concrets qui court-circuitent chaque filtre optionnel, valeur
    // factice non nulle quand hasX = false : jamais de "(:x IS NULL OR ...)" (plantage
    // Postgres dès que le type du paramètre nul est inconnu, voir CommandeRepo.search).
    @Query("SELECT c FROM CollecteOeufs c LEFT JOIN c.projet p LEFT JOIN c.batiment b WHERE c.farm.id = :farmId " +
        "AND c.initialisation.removed = false " +
        "AND (:hasProjet = false OR p.uniqueId = :projetUniqueId) " +
        "AND (:hasBatiment = false OR b.uniqueId = :batimentUniqueId)")
    Page<CollecteOeufs> search(@Param("farmId") Long farmId,
                                @Param("hasProjet") boolean hasProjet, @Param("projetUniqueId") String projetUniqueId,
                                @Param("hasBatiment") boolean hasBatiment, @Param("batimentUniqueId") String batimentUniqueId,
                                Pageable pageable);

    // Sert au calcul du taux de ponte récent (moyenne journalière des N derniers jours).
    // Fenêtre FERMÉE [debut, fin] : une saisie datée de demain (tolérée, voir DateSaisie)
    // ne doit pas entrer dans les N jours qui finissent aujourd'hui.
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false " +
        "AND c.date >= :debut AND c.date <= :fin")
    Integer sumOeufsByProjetIdEntre(@Param("projetId") Long projetId, @Param("debut") LocalDate debut, @Param("fin") LocalDate fin);

    // Cumul déjà collecté CE JOUR-LÀ, pour le projet entier (aucun bâtiment
    // sélectionné) — une poule ne pond qu'un œuf par JOUR, pas par collecte : deux
    // collectes le même jour (matin + soir) doivent être cumulées avant comparaison
    // à l'effectif vivant, voir CollecteOeufsImpl.effectifVivant/create/update.
    // excludeId : exclut la collecte en cours d'édition (update), sinon elle se
    // compterait deux fois contre elle-même. Jamais nul : -1 quand il n'y a rien à exclure
    // (création), voir EffectifVivantHelper.oeufsDejaCollectes.
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.date = :date AND c.initialisation.removed = false " +
        "AND c.id <> :excludeId")
    Integer sumOeufsByProjetIdAndDateExcluding(@Param("projetId") Long projetId, @Param("date") LocalDate date, @Param("excludeId") Long excludeId);

    // Même chose mais scopé à UN bâtiment précis (quand un bâtiment est sélectionné à
    // la saisie) — les autres bâtiments du même projet ont leur propre effectif,
    // donc leur propre plafond, indépendant de celui-ci.
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.batiment.id = :batimentId AND c.date = :date AND c.initialisation.removed = false " +
        "AND c.id <> :excludeId")
    Integer sumOeufsByBatimentIdAndDateExcluding(@Param("batimentId") Long batimentId, @Param("date") LocalDate date, @Param("excludeId") Long excludeId);

    // Totaux vie-entière (pas fenêtrés) à l'échelle de TOUTE LA FERME : plafond global
    // de la vente d'œufs (Finance), voir VenteOeufsImpl.
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.farm.id = :farmId AND c.initialisation.removed = false")
    Integer sumOeufsCollectesByFarmId(@Param("farmId") Long farmId);

    @Query("SELECT COALESCE(SUM(c.oeufsCasses), 0) FROM CollecteOeufs c " +
        "WHERE c.farm.id = :farmId AND c.initialisation.removed = false")
    Integer sumOeufsCassesByFarmId(@Param("farmId") Long farmId);

    @Query("SELECT COALESCE(SUM(c.oeufsNonUtilisables), 0) FROM CollecteOeufs c " +
        "WHERE c.farm.id = :farmId AND c.initialisation.removed = false")
    Integer sumOeufsNonUtilisablesByFarmId(@Param("farmId") Long farmId);

    // Totaux vie-entière PAR PROJET : servent à répartir proportionnellement chaque
    // vente farm-wide entre les projets contributeurs (voir VenteOeufsImpl —
    // la part de chaque projet dans la vente = sa part dans le stock disponible).
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false")
    Integer sumOeufsCollectesByProjetId(@Param("projetId") Long projetId);

    @Query("SELECT COALESCE(SUM(c.oeufsCasses), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false")
    Integer sumOeufsCassesByProjetId(@Param("projetId") Long projetId);

    @Query("SELECT COALESCE(SUM(c.oeufsNonUtilisables), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.initialisation.removed = false")
    Integer sumOeufsNonUtilisablesByProjetId(@Param("projetId") Long projetId);

    // Totaux PAR PROJET *dans un bâtiment de stockage précis* : servent à répartir
    // proportionnellement un transfert vers un magasin entre les projets contributeurs
    // DE CE BÂTIMENT (voir MagasinTransfertServiceImpl.disponibleParProjetDansBatimentStockage)
    // — remplace le calcul farm-wide par projet ci-dessus pour les transferts d'œufs,
    // maintenant que le stock physique est rattaché à un bâtiment, pas juste au projet.
    @Query("SELECT COALESCE(SUM(c.oeufsCollectes), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.magasinStockage.id = :magasinStockageId AND c.initialisation.removed = false")
    Integer sumOeufsCollectesByProjetIdAndMagasinStockageId(@Param("projetId") Long projetId, @Param("magasinStockageId") Long magasinStockageId);

    @Query("SELECT COALESCE(SUM(c.oeufsCasses), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.magasinStockage.id = :magasinStockageId AND c.initialisation.removed = false")
    Integer sumOeufsCassesByProjetIdAndMagasinStockageId(@Param("projetId") Long projetId, @Param("magasinStockageId") Long magasinStockageId);

    @Query("SELECT COALESCE(SUM(c.oeufsNonUtilisables), 0) FROM CollecteOeufs c " +
        "WHERE c.projet.id = :projetId AND c.magasinStockage.id = :magasinStockageId AND c.initialisation.removed = false")
    Integer sumOeufsNonUtilisablesByProjetIdAndMagasinStockageId(@Param("projetId") Long projetId, @Param("magasinStockageId") Long magasinStockageId);

    // Quels projets ont déjà déposé des œufs dans ce bâtiment de stockage — sert à
    // construire la carte "disponible par projet" sans avoir à connaître les projets
    // à l'avance.
    @Query("SELECT DISTINCT c.projet.id FROM CollecteOeufs c " +
        "WHERE c.magasinStockage.id = :magasinStockageId AND c.initialisation.removed = false")
    java.util.List<Long> findDistinctProjetIdsByMagasinStockageId(@Param("magasinStockageId") Long magasinStockageId);

    // Toutes les collectes vivantes d'un projet — sert à RapportJournalierServiceImpl à
    // reconstituer jour par jour NTO/NEC/non utilisables sur toute la vie du projet.
    @Query("SELECT c FROM CollecteOeufs c WHERE c.projet.id = :projetId AND c.initialisation.removed = false")
    java.util.List<CollecteOeufs> findAllByProjetId(@Param("projetId") Long projetId);

    // Magasin de stockage des dernières collectes d'un projet (le plus récent d'abord) :
    // défaut d'une réforme envoyée sans magasin par un ancien téléphone (ReformeStockage).
    @Query("SELECT c.magasinStockage FROM CollecteOeufs c WHERE c.projet.id = :projetId " +
        "AND c.initialisation.removed = false AND c.magasinStockage IS NOT NULL ORDER BY c.date DESC, c.id DESC")
    java.util.List<com.diafarms.ml.models.Magasin> findMagasinsStockageRecentsByProjetId(@Param("projetId") Long projetId,
                                                                                   org.springframework.data.domain.Pageable pageable);
}
