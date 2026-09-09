package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.PersonnelDTO;
import com.diafarms.ml.request.create.PersonnelCreate;

public interface PersonnelService {
    PersonnelDTO create(PersonnelCreate data);
    PersonnelDTO update(String uniqueId, PersonnelCreate data);
    List<PersonnelDTO> select();
    // Archive (ou restaure) — jamais une vraie suppression, un Personnel peut avoir
    // un historique de salaires/paiements à préserver. Archive aussi sa grille
    // salariale (Salaire) si elle existe, pour qu'elle disparaisse en même temps
    // des listes actives — voir PersonnelServiceImpl.
    String deleteOrRecover(String uniqueId);
}
