package com.diafarms.ml.services;

import java.time.LocalDate;
import java.util.List;

import com.diafarms.ml.DTO.ProjetVenteReelDTO;
import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.TransactionStatsDTO;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.request.others.RejectTransactionRequest;
import com.diafarms.ml.request.update.TransactionUpdate;

public interface TransactionService {

    TransactionDTO create(TransactionCreate data);

    /**
     * Crée une transaction "entrée" directement liée à une vente (une ligne de
     * VenteOeufsRepartition/VenteReformeRepartition), sans passer par le DTO de
     * saisie manuelle — statut EN_ATTENTE comme toute transaction créée
     * normalement, même workflow de validation, pas de bypass. Appelée depuis
     * VenteOeufsImpl/VenteReformeImpl.create() une fois par projet contributeur
     * (projet n'est jamais null ici : chaque part de la vente est directement
     * attribuée à SON projet, pour que computeChiffreAffairesReel(projet) reste
     * exact). farm est pris en paramètre explicite pour rester cohérent avec le
     * reste de la Transaction sans dépendre d'un aller-retour projet.getFarm().
     * creePar : qui a déclenché la vente (VenteOeufs/VenteReforme), pour que la page
     * Ventes puisse restreindre un FINANCIER à ses propres ventes.
     */
    TransactionDTO createFromSource(Projets projet, Farm farm, Double montant, String categorie, LocalDate date,
                                     String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar);

    /**
     * Crée une transaction "sortie" commune (pas de projet, pas de client) tracée
     * jusqu'à sa source — utilisée par SalaireServiceImpl.payer pour que "Payer le
     * salaire" génère une vraie Transaction (catégorie "Salaires",
     * SourceTransaction.SALAIRE) au lieu de laisser l'utilisateur ressaisir une
     * transaction manuelle non structurée. Même statut VALIDE par défaut que create().
     */
    TransactionDTO createSortieCommune(Farm farm, Double montant, String categorie, LocalDate date,
                                        String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar);

    /** Bascule removed sur la transaction liée à une vente supprimée/restaurée
     * (retrouvée via sourceUniqueId) — pas de recette fantôme après suppression. */
    void toggleRemovedBySource(String sourceUniqueId);

    /**
     * Crée, met à jour ou retire la transaction "sortie" liée à une source
     * (Alimentation/Soins/Vaccination/Investissement) — point d'entrée unique appelé
     * aussi bien à la création qu'à la modification de la source, pour que le coût
     * saisi côté Production/Investissement compte automatiquement comme une vraie
     * sortie en Comptabilité, sans ressaisie manuelle. projet à null = dépense commune
     * (pas rattachée à un projet précis, ex: investissement COMMUN) ; montant nul ou
     * <= 0 retire (removed=true) la transaction existante sans la supprimer
     * définitivement, plutôt que de laisser une sortie à 0 FCFA polluer les rapports.
     */
    void syncSortie(Projets projet, Farm farm, Double montant, String categorie, LocalDate date,
                     String description, SourceTransaction sourceType, String sourceUniqueId, Utilisateurs creePar);

    /** Met à jour le montant de la transaction liée à une vente modifiée. */
    void updateMontantBySource(String sourceUniqueId, Double montant);

    /** Met à jour la description de la transaction liée à une vente modifiée — utilisé
     * quand seul le montant rapporté change (pas la quantité/le montant théorique) :
     * pas de nouvelle répartition, juste rafraîchir le texte de traçabilité de l'écart. */
    void updateDescriptionBySource(String sourceUniqueId, String description);

    TransactionDTO update(String uniqueId, TransactionUpdate data);

    String deleteOrRecover(String uniqueId);

    TransactionDTO valider(String uniqueId);

    TransactionDTO rejeter(String uniqueId, RejectTransactionRequest data);

    /**
     * financierUniqueId/vendeurUniqueId/dateDebut/dateFin sont optionnels : voir
     * TransactionServiceImpl.resolveProjetIdsScope (restriction par projet, pour
     * Comptabilité) et resolveVendeurScopeForList (restriction par créateur, pour
     * Ventes) — deux axes différents. Un FINANCIER pur (voir hasOnlyRole côté front)
     * est de toute façon forcé sur ses propres ventes, quel que soit vendeurUniqueId
     * reçu — seul un ADMIN/SUPER_ADMIN peut choisir "voir comme" un vendeur précis.
     */
    PaginatedResponse<TransactionDTO> list(int page, int size, String search, TypeTransaction type, StatutTransaction statut,
                                            String projetUniqueId, String financierUniqueId, String vendeurUniqueId,
                                            LocalDate dateDebut, LocalDate dateFin);

    TransactionStatsDTO getStats(String financierUniqueId, LocalDate dateDebut, LocalDate dateFin);

    /** Montant théorique/réel des ventes (œufs + réforme) PAR PROJET sur la période —
     * "Entrées (théorique)" par projet existe déjà via list()/le web (somme directe
     * des Transactions), mais aucune vue par projet du réel n'existait avant : voir
     * TransactionServiceImpl pour le détail du calcul au prorata. Ferme entière, pas
     * de scope RESPONSABLE/COMPTABLE ici — c'est Reporting.tsx qui filtre côté client
     * aux projets pertinents pour l'utilisateur courant. */
    List<ProjetVenteReelDTO> getVentesReelParProjet(LocalDate dateDebut, LocalDate dateFin);
}
