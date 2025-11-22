package com.diafarms.ml.services;

import java.util.List;

import com.diafarms.ml.DTO.RoleDto;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.selectClass.RolesSelect;


public interface RolesServices {
    
    RoleDto create(Roles role);
    RoleDto update(Roles role, String uniqueIdRole);
    String deleteOrRecover(String uniqueIdRole);
    String archive(String uniqueIdRole);
    PaginatedResponse<RoleDto> findAll(int page, int size, String type);
    List<RolesSelect> roleSelect();
    PaginatedResponse<RoleDto> search(String search);
}
