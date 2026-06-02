package com.diafarms.ml.services;

import com.diafarms.ml.DTO.LogsDTO;
import com.diafarms.ml.models.Logs;
import com.diafarms.ml.others.PaginatedResponse;

public interface LogsServices {

    /**
     * Adds a log entry for a user action.
     *
     * @param userId the ID of the user performing the action
     * @param entityId the ID of the affected entity (optional)
     * @param entityType the type of entity (User, Role, etc.)
     * @param action the description of the action
     * @return the created log entry
     */
    Logs addLogs(Long userId, Long entityId, String entityType, String action);
    String delete(String uniqueId);
    PaginatedResponse<Logs> getAllByIdAction(Long idAction, int page, int size);
    PaginatedResponse<Logs> getAllByNomClass(String nomClass, int page, int size);
    PaginatedResponse<LogsDTO> getAll(int page, int size, String search);

}
