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
    // moment-là), voir ClientServiceImpl.payerDette.
    ClientDTO payerDette(String uniqueId, Double montant, String description);
}
