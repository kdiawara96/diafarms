package com.diafarms.ml.services;

import com.diafarms.ml.DTO.StockOeufsDTO;
import com.diafarms.ml.DTO.VenteOeufsDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.VenteOeufsCreate;
import com.diafarms.ml.request.update.VenteOeufsUpdate;

public interface VenteOeufsService {

    VenteOeufsDTO create(VenteOeufsCreate data);

    VenteOeufsDTO update(String uniqueId, VenteOeufsUpdate data);

    String deleteOrRecover(String uniqueId);

    PaginatedResponse<VenteOeufsDTO> list(int page, int size);

    StockOeufsDTO getStock();
}
