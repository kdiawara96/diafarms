package com.diafarms.ml.services;


import java.util.List;

import com.diafarms.ml.DTO.AlimentationDTO;
import com.diafarms.ml.request.create.AlimentationCreate;
public interface AlimentationService {

    AlimentationDTO save(AlimentationCreate data, String uniqueIdProjet);
    AlimentationDTO update(String uniqueId, AlimentationCreate data);
    AlimentationDTO delete(String uniqueId);
    List<AlimentationDTO> findByProjetUniqueId(String uniqueIdProjet);
    AlimentationDTO findByUniqueId(String uniqueId);
}