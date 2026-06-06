package com.diafarms.ml.services;

import com.diafarms.ml.DTO.VaccinationDTO;
import com.diafarms.ml.request.create.VaccinCreate;

public interface VaccinationService {
    
    VaccinationDTO save(VaccinCreate data, String uniqueIdProjet);
    VaccinationDTO update(String uniqueId, VaccinationDTO data);
    VaccinationDTO delete(String uniqueId);

}
