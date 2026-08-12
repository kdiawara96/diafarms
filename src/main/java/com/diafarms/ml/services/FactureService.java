package com.diafarms.ml.services;

import com.diafarms.ml.DTO.FactureDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.FactureGenerateRequest;

public interface FactureService {
    // Crée la facture depuis une vente (VENTE_OEUFS/VENTE_REFORME) ou une commande
    // CONVERTIE — voir FactureServiceImpl pour la résolution de la source.
    FactureDTO genererDepuis(FactureGenerateRequest data);
    // Enregistre un vrai paiement sur cette facture précise (Transaction + SoldeClient,
    // réutilise ClientService.payerDette) et met à jour montantPaye/statut.
    FactureDTO marquerPayee(String uniqueId, Double montant);
    byte[] genererPdf(String uniqueId);
    PaginatedResponse<FactureDTO> list(int page, int size, String statut, String clientUniqueId);
}
