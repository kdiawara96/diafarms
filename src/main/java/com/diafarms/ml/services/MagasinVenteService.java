package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.MagasinVenteDTO;
import com.diafarms.ml.DTO.StockMagasinDTO;
import com.diafarms.ml.request.create.MagasinVenteCreate;

public interface MagasinVenteService {
    MagasinVenteDTO create(MagasinVenteCreate data);
    MagasinVenteDTO update(String uniqueId, MagasinVenteCreate data);
    String deleteOrRecover(String uniqueId);
    List<MagasinVenteDTO> list();
    StockMagasinDTO getStock(String uniqueId);
}
