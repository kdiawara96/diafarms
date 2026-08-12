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
    CommandeDTO convertirEnVente(String uniqueId);
    String deleteOrRecover(String uniqueId);
    PaginatedResponse<CommandeDTO> list(int page, int size, String statut, String clientUniqueId);
}
