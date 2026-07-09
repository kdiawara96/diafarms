package com.diafarms.ml.services;

import com.diafarms.ml.DTO.TransactionDTO;
import com.diafarms.ml.DTO.TransactionStatsDTO;
import com.diafarms.ml.enums.StatutTransaction;
import com.diafarms.ml.enums.TypeTransaction;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.TransactionCreate;
import com.diafarms.ml.request.others.RejectTransactionRequest;
import com.diafarms.ml.request.update.TransactionUpdate;

public interface TransactionService {

    TransactionDTO create(TransactionCreate data);

    TransactionDTO update(String uniqueId, TransactionUpdate data);

    String deleteOrRecover(String uniqueId);

    TransactionDTO valider(String uniqueId);

    TransactionDTO rejeter(String uniqueId, RejectTransactionRequest data);

    PaginatedResponse<TransactionDTO> list(int page, int size, String search, TypeTransaction type, StatutTransaction statut);

    TransactionStatsDTO getStats();
}
