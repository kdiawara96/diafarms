package com.diafarms.ml.services;

import com.diafarms.ml.models.Logs;
import com.diafarms.ml.others.PaginatedResponse;

public interface LogsServices {
    
    Logs addLogs(Long id_action, String nomClass, String texteAction);
    String delete(String uniqueId);
    PaginatedResponse<Logs> getAllByIdAction(Long idAction, int page, int size, String type);
    PaginatedResponse<Logs> getAllByNomClass(String nomClass, int page, int size, String type);
    PaginatedResponse<Logs> getAll(int page, int size, String type);

    PaginatedResponse<Logs> search(String search);

}
