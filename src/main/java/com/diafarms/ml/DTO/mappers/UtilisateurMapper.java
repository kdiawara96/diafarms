package com.diafarms.ml.DTO.mappers;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import com.diafarms.ml.DTO.UtilisateursDto;
import com.diafarms.ml.models.Utilisateurs;

@Mapper(componentModel = "spring", uses = {RoleMapper.class})
public interface UtilisateurMapper {

    @Mapping(source = "initialisation.createdAt", target = "createdAt")
    @Mapping(source = "initialisation.updatedAt", target = "updatedAt")
    @Mapping(target = "password", ignore = true)
    @Mapping(source = "statut", target = "statut")
    UtilisateursDto toDto(Utilisateurs user);

    List<UtilisateursDto> toDtoList(List<Utilisateurs> users);
}
