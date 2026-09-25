package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.EvolutionPoidsDTO;
import com.diafarms.ml.DTO.SessionPeseeDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.others.SessionPeseeSyncRequest;
import com.diafarms.ml.request.others.SessionPeseeWebRequest;

public interface SessionPeseeService {

    SessionPeseeDTO sync(SessionPeseeSyncRequest request);

    PaginatedResponse<SessionPeseeDTO> list(String projetUniqueId, String statut, int page, int size);

    SessionPeseeDTO detail(String uniqueId);

    List<EvolutionPoidsDTO> evolution(String projetUniqueId);

    // Web (utilisateur sans téléphone).
    SessionPeseeDTO creerWeb(SessionPeseeWebRequest request);

    SessionPeseeDTO ajouterWeb(String sessionUniqueId, SessionPeseeWebRequest request);

    SessionPeseeDTO modifierWeb(String sessionUniqueId, String peseeUniqueId, SessionPeseeWebRequest request);

    SessionPeseeDTO annulerWeb(String sessionUniqueId, String peseeUniqueId);

    SessionPeseeDTO terminerWeb(String sessionUniqueId, SessionPeseeWebRequest request);
}
