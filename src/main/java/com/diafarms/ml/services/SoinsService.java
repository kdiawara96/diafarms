package com.diafarms.ml.services;

import com.diafarms.ml.DTO.SoinsDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.SoinsCreate;
import com.diafarms.ml.request.update.SoinsUpdate;

public interface SoinsService {

    SoinsDTO create(SoinsCreate data);

    SoinsDTO update(String uniqueId, SoinsUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<SoinsDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId);
}
