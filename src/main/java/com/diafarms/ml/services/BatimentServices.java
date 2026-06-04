package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.BatimentsDTO;
import com.diafarms.ml.models.Batiment;

public interface BatimentServices {
    
    BatimentsDTO create(Batiment batiment);
    BatimentsDTO update(Batiment batiment);
    String deleteOrRecover(String uniqueIdBatiment);
    List<BatimentsDTO> findAll();
    List<BatimentsDTO> search(String search);
    List<BatimentsDTO> select();
}
