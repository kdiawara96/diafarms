package com.diafarms.ml.services;


import java.util.List;

import com.diafarms.ml.DTO.AlimentationDTO;
import com.diafarms.ml.request.create.AlimentationCreate;
import com.diafarms.ml.request.update.AlimentationUpdate;
public interface AlimentationService {

    AlimentationDTO save(AlimentationCreate data, String uniqueIdProjet);
    AlimentationDTO update(String uniqueId, AlimentationUpdate data);
    AlimentationDTO delete(String uniqueId);
    List<AlimentationDTO> findByProjetUniqueId(String uniqueIdProjet);
    AlimentationDTO findByUniqueId(String uniqueId);
}