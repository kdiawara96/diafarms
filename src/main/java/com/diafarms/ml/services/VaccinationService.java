package com.diafarms.ml.services;

import com.diafarms.ml.DTO.VaccinationDTO;
import com.diafarms.ml.request.create.VaccinCreate;
import com.diafarms.ml.request.update.VaccinUpdate;

public interface VaccinationService {
    
    VaccinationDTO save(VaccinCreate data, String uniqueIdProjet);
    VaccinationDTO update(String uniqueId, VaccinUpdate data);
    VaccinationDTO delete(String uniqueId);

}
