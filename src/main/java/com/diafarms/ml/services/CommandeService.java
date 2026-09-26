package com.diafarms.ml.services;

import com.diafarms.ml.DTO.CommandeDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.CommandeCreate;
import com.diafarms.ml.request.create.PaiementClientCreate;

public interface CommandeService {
    CommandeDTO create(CommandeCreate data);
    CommandeDTO update(String uniqueId, CommandeCreate data);
    CommandeDTO confirmer(String uniqueId);

    /** Annule une commande n'ayant encore rien livré (sinon : cloturer). motif obligatoire
     * (MotifSuppressionRequest) ; rembourserAcompte rend en argent ce qui restait
     * disponible côté de cette commande, sinon ça reste une avance sur le compte client. */
    CommandeDTO annuler(String uniqueId, String motif, boolean rembourserAcompte, String mode);

    /** Clôture une commande livrée en partie seulement (on arrête là, le reste n'est plus
     * livré) — motif obligatoire. Un éventuel trop-perçu reste en avance sur le client. */
    CommandeDTO cloturer(String uniqueId, String motif);

    // Crée la vente (VenteOeufs/VenteReforme) correspondante — réutilise directement
    // VenteOeufsService/VenteReformeService.create, marque la commande CONVERTIE.
    // Équivaut désormais à livrer(uniqueId, null, 0.0, null) : livre tout ce qui reste,
    // en un coup, sans compter de nouvel argent.
    CommandeDTO convertirEnVente(String uniqueId);

    /** Livre une commande, en partie ou en totalité (grosse commande livrée au fil de
     * la collecte, en plusieurs fois) — crée une vente pour la quantité livrée cette
     * fois-ci, sans jamais dépasser ce qu'il reste. quantite null = tout ce qui reste
     * (comportement de convertirEnVente). montantRecu = argent NOUVEAU reçu à cette
     * livraison précise (0/null si rien de neuf : l'acompte/les paiements déjà faits sur
     * cette commande ont déjà été portés au solde du client). mode = mode de paiement de
     * cet argent nouveau, ESPECES par défaut. */
    // poidsTotalKg / prixKg : commande de réforme au KILO seulement (poids pesé des sujets
    // livrés, obligatoire ; prix/kg optionnel, défaut = prixKgEstime de la commande).
    // date / heure : date réelle de la livraison (AAAA-MM-JJ, HH:mm), pour une livraison
    // saisie hors ligne et envoyée plus tard ; absentes = aujourd'hui, sans heure. Jamais
    // dans le futur. La vente créée, sa transaction et l'argent reçu portent cette date.
    CommandeDTO livrer(String uniqueId, Integer quantite, Double montantRecu, String mode,
                       Double poidsTotalKg, Double prixKg, String date, String heure);

    default CommandeDTO livrer(String uniqueId, Integer quantite, Double montantRecu, String mode,
                               Double poidsTotalKg, Double prixKg) {
        return livrer(uniqueId, quantite, montantRecu, mode, poidsTotalKg, prixKg, null, null);
    }

    default CommandeDTO livrer(String uniqueId, Integer quantite, Double montantRecu, String mode) {
        return livrer(uniqueId, quantite, montantRecu, mode, null, null);
    }

    /** Enregistre un paiement (acompte ou règlement) rattaché à cette commande —
     * PaiementClientCreate sans clientUniqueId (déduit de la commande). */
    CommandeDTO enregistrerPaiement(String uniqueId, PaiementClientCreate data);

    String deleteOrRecover(String uniqueId);
    PaginatedResponse<CommandeDTO> list(int page, int size, String statut, String clientUniqueId);
}
