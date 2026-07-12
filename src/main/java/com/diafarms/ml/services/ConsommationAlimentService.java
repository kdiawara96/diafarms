package com.diafarms.ml.services;

import com.diafarms.ml.DTO.ConsommationAlimentDTO;
import com.diafarms.ml.DTO.StockAlimentDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ConsommationAlimentCreate;
import com.diafarms.ml.request.update.ConsommationAlimentUpdate;

public interface ConsommationAlimentService {

    ConsommationAlimentDTO create(ConsommationAlimentCreate data);

    ConsommationAlimentDTO update(String uniqueId, ConsommationAlimentUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<ConsommationAlimentDTO> list(int page, int size, String projetUniqueId, String batimentUniqueId);

    StockAlimentDTO getStock(String projetUniqueId);
}
