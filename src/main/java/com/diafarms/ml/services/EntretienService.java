package com.diafarms.ml.services;

import com.diafarms.ml.DTO.EntretienDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.EntretienCreate;
import com.diafarms.ml.request.update.EntretienUpdate;

public interface EntretienService {

    EntretienDTO create(EntretienCreate data);

    EntretienDTO update(String uniqueId, EntretienUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<EntretienDTO> list(int page, int size, String search, String batimentUniqueId, String niveau, String type);
}
