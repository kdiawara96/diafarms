package com.diafarms.ml.services;

import java.time.LocalDate;

import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.TransactionStatsDTO;
import com.diafarms.ml.enums.SourceTransaction;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Projets;
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
     */
    TransactionDTO createFromSource(Projets projet, Farm farm, Double montant, String categorie, LocalDate date,
                                     String description, SourceTransaction sourceType, String sourceUniqueId);

    /** Bascule removed sur la transaction liée à une vente supprimée/restaurée
     * (retrouvée via sourceUniqueId) — pas de recette fantôme après suppression. */
    void toggleRemovedBySource(String sourceUniqueId);

    /** Met à jour le montant de la transaction liée à une vente modifiée. */
    void updateMontantBySource(String sourceUniqueId, Double montant);

    TransactionDTO update(String uniqueId, TransactionUpdate data);

    String deleteOrRecover(String uniqueId);

    TransactionDTO valider(String uniqueId);

    TransactionDTO rejeter(String uniqueId, RejectTransactionRequest data);

    PaginatedResponse<TransactionDTO> list(int page, int size, String search, TypeTransaction type, StatutTransaction statut, String projetUniqueId);

    TransactionStatsDTO getStats();
}
