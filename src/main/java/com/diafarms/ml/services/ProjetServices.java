package com.diafarms.ml.services;

import com.diafarms.ml.DTO.ProjetsDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ProjetCreate;
import com.diafarms.ml.request.update.ProjetUpdate;

public interface ProjetServices {

    PaginatedResponse<ProjetsDTO> getAllProjets(int page, int size, String search, String filter);
    ProjetsDTO getProjetByUniqueId(String uniqueId);
    ProjetsDTO createProjet(ProjetCreate data);
    ProjetsDTO updateProjet(String uniqueId, ProjetUpdate data);
    String deleteOrRecoverProjet(String uniqueId);

}
