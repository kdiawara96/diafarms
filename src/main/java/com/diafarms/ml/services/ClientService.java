package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.ClientDTO;
import com.diafarms.ml.DTO.ClientReportDTO;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.create.ClientCreate;

public interface ClientService {
    ClientDTO create(ClientCreate data);
    ClientDTO update(String uniqueId, ClientCreate data);
    String deleteOrRecover(String uniqueId);
    List<ClientDTO> select();
    PaginatedResponse<ClientDTO> list(int page, int size, String search);
    ClientReportDTO getReport(String uniqueId);
}
