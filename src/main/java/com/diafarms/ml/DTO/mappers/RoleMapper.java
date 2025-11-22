package com.diafarms.ml.DTO.mappers;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.diafarms.ml.DTO.RoleDto;
import com.diafarms.ml.models.Roles;

@Mapper(componentModel = "spring")
public interface RoleMapper {
    
    @Mapping(source = "initialisation.createdAt", target = "createdAt")
    @Mapping(source = "initialisation.updatedAt", target = "updatedAt")
    RoleDto toDto(Roles role);

    List<RoleDto> toDtoList(List<Roles> roles);
}
