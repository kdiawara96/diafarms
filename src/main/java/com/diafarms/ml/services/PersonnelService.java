package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.PersonnelDTO;
import com.diafarms.ml.request.create.PersonnelCreate;

public interface PersonnelService {
    PersonnelDTO create(PersonnelCreate data);
    PersonnelDTO update(String uniqueId, PersonnelCreate data);
    List<PersonnelDTO> select();
}
