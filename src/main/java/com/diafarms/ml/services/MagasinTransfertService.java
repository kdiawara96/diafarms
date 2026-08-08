package com.diafarms.ml.services;

import com.diafarms.ml.DTO.MagasinTransfertDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.MagasinTransfertCreate;

public interface MagasinTransfertService {
    MagasinTransfertDTO create(MagasinTransfertCreate data);
    PaginatedResponse<MagasinTransfertDTO> list(String magasinUniqueId, int page, int size);
    // Stock du projet pas encore transféré vers aucun magasin — plafond d'un nouveau transfert.
    int disponibleATransfererDepuisProjet(String projetUniqueId, String type);
}
