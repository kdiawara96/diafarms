package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.ClientDTO;
import com.diafarms.ml.DTO.ClientReportDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ClientCreate;

public interface ClientService {
    ClientDTO create(ClientCreate data);
    ClientDTO update(String uniqueId, ClientCreate data);
    String deleteOrRecover(String uniqueId);
    List<ClientDTO> select();
    PaginatedResponse<ClientDTO> list(int page, int size, String search);
    ClientReportDTO getReport(String uniqueId);
    // Enregistre un paiement du client sur sa dette en cours (SoldeClient) — génère
    // aussi une Transaction "entrée" (l'argent rentre vraiment dans la caisse à ce
    // moment-là), voir ClientServiceImpl.payerDette. Catégorie "Remboursement client"
    // par défaut (voir la surcharge ci-dessous pour une autre catégorie, ex: un acompte
    // de commande — CommandeServiceImpl).
    ClientDTO payerDette(String uniqueId, Double montant, String description);
    ClientDTO payerDette(String uniqueId, Double montant, String categorie, String description);

    /** Rend en argent une avance déjà payée par ce client (jumeau de payerDette, dans
     * l'autre sens) — génère une Transaction "sortie" et fait remonter le solde vers
     * zéro. Refusé si le client n'a pas d'avance (solde >= 0), ou si le montant dépasse
     * l'avance disponible. Voir ClientServiceImpl.rembourser. */
    ClientDTO rembourser(String uniqueId, Double montant, String description);
}
