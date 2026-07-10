package com.diafarms.ml.services;

import com.diafarms.ml.DTO.CollecteOeufsDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.CollecteOeufsCreate;
import com.diafarms.ml.request.update.CollecteOeufsUpdate;

public interface CollecteOeufsService {

    CollecteOeufsDTO create(CollecteOeufsCreate data);

    CollecteOeufsDTO update(String uniqueId, CollecteOeufsUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<CollecteOeufsDTO> list(int page, int size, String projetUniqueId, String batimentUniqueId);
}
