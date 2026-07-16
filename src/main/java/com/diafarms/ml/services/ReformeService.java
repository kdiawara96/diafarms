package com.diafarms.ml.services;

import com.diafarms.ml.DTO.EffectifReformeDTO;
import com.diafarms.ml.DTO.ReformeDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ReformeCreate;
import com.diafarms.ml.request.update.ReformeUpdate;

public interface ReformeService {

    ReformeDTO create(ReformeCreate data);

    ReformeDTO update(String uniqueId, ReformeUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<ReformeDTO> list(int page, int size, String search, String projetUniqueId, String batimentUniqueId);

    EffectifReformeDTO getEffectif(String projetUniqueId);
}
