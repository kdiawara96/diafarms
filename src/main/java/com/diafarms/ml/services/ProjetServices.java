package com.diafarms.ml.services;

import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ProjetCreate;

public interface ProjetServices {

    PaginatedResponse<ProjetsDTO> getAllProjets(int page, int size, String search, String filter);
    ProjetsDTO getProjetByUniqueId(String uniqueId);
    ProjetsDTO createProjet(ProjetCreate data);
    String deleteOrRecoverProjet(String uniqueId);

}
