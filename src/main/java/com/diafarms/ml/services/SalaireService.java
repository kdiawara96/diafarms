package com.diafarms.ml.services;

import com.diafarms.ml.DTO.PaiementSalaireDTO;
import com.diafarms.ml.DTO.SalaireDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.SalaireDefinirRequest;
import com.diafarms.ml.request.others.SalairePayerRequest;

public interface SalaireService {
    // Upsert : crée le salaire de base de l'employé s'il n'existe pas, met à jour
    // montantMensuel sinon (un seul Salaire par employé, voir Salaire.employe unique).
    SalaireDTO definir(SalaireDefinirRequest data);
    // Génère une vraie Transaction (SORTIE, "Salaires", SourceTransaction.SALAIRE) —
    // voir TransactionService.createSortieCommune — au plus un paiement par période.
    PaiementSalaireDTO payer(SalairePayerRequest data);
    PaginatedResponse<SalaireDTO> list(int page, int size);
    PaginatedResponse<PaiementSalaireDTO> listPaiements(String employeUniqueId, int page, int size);
}
