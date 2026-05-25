package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.RoleDTO;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.selectClass.RolesSelect;


public interface RolesServices {
    
    RoleDTO create(Roles role);
    RoleDTO update(Roles role, String uniqueIdRole);
    String deleteOrRecover(String uniqueIdRole);
    String archive(String uniqueIdRole);
    PaginatedResponse<RoleDTO> findAll(int page, int size, String type);
    List<RolesSelect> roleSelect();
    PaginatedResponse<RoleDTO> search(String search);
}
