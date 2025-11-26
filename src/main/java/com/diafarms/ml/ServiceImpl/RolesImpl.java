package com.diafarms.ml.ServiceImpl;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.RoleDto;
import com.diafarms.ml.DTO.mappers.RoleMapper;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.RolesRepo;
import com.diafarms.ml.selectClass.RolesSelect;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.RolesServices;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class RolesImpl implements RolesServices{
    
    private final RolesRepo repo;
    private final LogsServices logs;
    private final RoleMapper roleMapper;

    @Override
    public RoleDto create(Roles role) {
    
        if (repo.findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(role.getRole().toUpperCase()) != null) {
            throw new RuntimeException("Le rôle existe déjà !");
        }

        role.setUniqueId(Initialisation.generateUniqueId());
        role.setInitialisation(Initialisation.init());

        Roles saved = repo.save(role);
        logs.addLogs(saved.getId(), "Roles", "Ajout d'un rôle avec succès !");

        return roleMapper.toDto(saved); // 🔥 PLUS de fromRole
    }

    @Override
    public RoleDto update(Roles role, String uniqueIdRole) {

        Roles dbRole = repo.findByUniqueId(uniqueIdRole)
            .orElseThrow(() -> new RuntimeException("Le rôle avec ID '" + uniqueIdRole + "' n'existe pas !"));

        Roles existingRole = repo.findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                role.getRole().toUpperCase()
        );

        if (existingRole != null && !existingRole.getUniqueId().equals(uniqueIdRole)) {
            throw new RuntimeException("Le rôle existe déjà !");
        }

        dbRole.setRole(role.getRole().toUpperCase());
        Roles updated = repo.save(dbRole);

        logs.addLogs(dbRole.getId(), "Roles", "Mise à jour d'un rôle");

        return roleMapper.toDto(updated); // 🔥
    }

    @Override
    public String deleteOrRecover(String uniqueIdRole) {

        Roles dbRole = repo.findByUniqueId(uniqueIdRole)
            .orElseThrow(() -> new RuntimeException("Le rôle avec ID '" + uniqueIdRole + "' n'existe pas !"));

        boolean isNowRemoved = !dbRole.getInitialisation().getRemoved();
        dbRole.getInitialisation().setRemoved(isNowRemoved);
        repo.save(dbRole);

        String action = isNowRemoved ? "Suppression" : "Récupération";
        logs.addLogs(dbRole.getId(), "Roles", action + " d'un rôle");

        return action + " réussie";
    }

    @Override
    public PaginatedResponse<RoleDto> findAll(int page, int size, String type) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createAt"));
        Page<Roles> rolePage = Page.empty();

        if (type.equals("folder")) {
            rolePage = repo.findByInitialisationRemovedFalseAndInitialisationArchiveFalse(pageable);
        } else if (type.equals("archive")) {
            rolePage = repo.findByInitialisationRemovedFalseAndInitialisationArchiveTrue(pageable);
        } else if (type.equals("trash")) {
            rolePage = repo.findByInitialisationRemovedTrue(pageable);
        }

        List<RoleDto> roleDtos = roleMapper.toDtoList(rolePage.getContent()); // 🔥

        return new PaginatedResponse<>(
                roleDtos,
                page,
                rolePage.getTotalPages(),
                rolePage.getTotalElements(),
                size
        );
    }

    @Override
    public List<RolesSelect> roleSelect() {
        List<Roles> roles = repo.findAllByInitialisationRemovedFalseAndInitialisationArchiveFalse();

        // 🔥 Pas de RoleDto, donc pas de Mapper ici
        return roles.stream()
                .map(RolesSelect::getLitRole)
                .toList();
    }

    @Override
    public String archive(String uniqueIdRole) {

        Roles dbRole = repo.findByUniqueId(uniqueIdRole)
            .orElseThrow(() -> new RuntimeException("Le rôle avec ID '" + uniqueIdRole + "' n'existe pas !"));

        boolean isArchive = !dbRole.getInitialisation().getArchive();
        dbRole.getInitialisation().setArchive(isArchive);
        repo.save(dbRole);

        String action = isArchive ? "Archivage" : "Récupération";
        logs.addLogs(dbRole.getId(), "Roles", action + " d'un rôle");

        return action + " réussie";
    }

    @Override
    public PaginatedResponse<RoleDto> search(String search) {

        List<Roles> roles = repo.searchRoles(search.trim());
        List<RoleDto> dtos = roleMapper.toDtoList(roles); // 🔥 

        return new PaginatedResponse<>(
                dtos,
                1,
                1,
                dtos.size(),
                dtos.size()
        );
    }
}

