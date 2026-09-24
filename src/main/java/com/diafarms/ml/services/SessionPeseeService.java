package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.EvolutionPoidsDTO;
import com.diafarms.ml.DTO.SessionPeseeDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.SessionPeseeSyncRequest;

public interface SessionPeseeService {

    SessionPeseeDTO sync(SessionPeseeSyncRequest request);

    PaginatedResponse<SessionPeseeDTO> list(String projetUniqueId, String statut, int page, int size);

    SessionPeseeDTO detail(String uniqueId);

    List<EvolutionPoidsDTO> evolution(String projetUniqueId);
}
