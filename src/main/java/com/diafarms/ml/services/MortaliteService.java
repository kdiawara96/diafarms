package com.diafarms.ml.services;

import com.diafarms.ml.DTO.MortaliteDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.MortaliteCreate;
import com.diafarms.ml.request.update.MortaliteUpdate;

public interface MortaliteService {

    MortaliteDTO create(MortaliteCreate data);

    MortaliteDTO update(String uniqueId, MortaliteUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<MortaliteDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId);
}
