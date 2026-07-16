package com.diafarms.ml.services;

import com.diafarms.ml.DTO.StockReformeDTO;
import com.diafarms.ml.DTO.VenteReformeDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.VenteReformeCreate;
import com.diafarms.ml.request.update.VenteReformeUpdate;

public interface VenteReformeService {

    VenteReformeDTO create(VenteReformeCreate data);

    VenteReformeDTO update(String uniqueId, VenteReformeUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<VenteReformeDTO> list(int page, int size);

    StockReformeDTO getStock();
}
