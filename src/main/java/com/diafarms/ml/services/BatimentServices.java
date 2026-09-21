package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.BatimentsDTO;
import com.diafarms.ml.models.Batiment;
import com.diafarms.ml.others.PaginatedResponse;

public interface BatimentServices {

    BatimentsDTO create(Batiment batiment);
    BatimentsDTO update(Batiment batiment);
    String deleteOrRecover(String uniqueIdBatiment);
    List<BatimentsDTO> findAll();
    List<BatimentsDTO> search(String search);
    List<BatimentsDTO> select();

    /** TOUS les poulaillers actifs de la ferme, occupés ou non (select() ne renvoie que les
     * poulaillers LIBRES, pour la création d'un projet) : sert à choisir un poulailler pour
     * une dépense. */
    List<BatimentsDTO> tous();
    PaginatedResponse<BatimentsDTO> listPaginated(int page, int size, String search);
}
