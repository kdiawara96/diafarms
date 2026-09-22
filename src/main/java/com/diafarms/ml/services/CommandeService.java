package com.diafarms.ml.services;

import com.diafarms.ml.DTO.CommandeDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.CommandeCreate;

public interface CommandeService {
    CommandeDTO create(CommandeCreate data);
    CommandeDTO update(String uniqueId, CommandeCreate data);
    CommandeDTO confirmer(String uniqueId);
    CommandeDTO annuler(String uniqueId);
    // Crée la vente (VenteOeufs/VenteReforme) correspondante — réutilise directement
    // VenteOeufsService/VenteReformeService.create, marque la commande CONVERTIE.
    // Équivaut désormais à livrer(uniqueId, null, 0.0) : livre tout ce qui reste, en un
    // coup, sans compter de nouvel argent (l'acompte a déjà été encaissé à la création).
    CommandeDTO convertirEnVente(String uniqueId);

    /** Livre une commande, en partie ou en totalité (grosse commande livrée au fil de
     * la collecte, en plusieurs fois) — crée une vente pour la quantité livrée cette
     * fois-ci, sans jamais dépasser ce qu'il reste. quantite null = tout ce qui reste
     * (comportement de convertirEnVente). montantRecu = argent NOUVEAU reçu à cette
     * livraison précise (0 si rien de neuf : l'acompte/les paiements déjà faits sur
     * cette commande ont déjà été portés au solde du client, voir create()/update()). */
    CommandeDTO livrer(String uniqueId, Integer quantite, Double montantRecu);
    String deleteOrRecover(String uniqueId);
    PaginatedResponse<CommandeDTO> list(int page, int size, String statut, String clientUniqueId);
}
