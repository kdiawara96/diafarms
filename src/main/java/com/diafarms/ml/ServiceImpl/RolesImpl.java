package com.diafarms.ml.ServiceImpl;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.RoleDTO;
import com.diafarms.ml.DTO.mappers.RoleMapper;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
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
    private final OtherService OtherService;

    @Override
    public RoleDTO create(Roles role) {
    
        if (repo.findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(role.getRole().toUpperCase()) != null) {
            throw new RuntimeException("Le rôle existe déjà !");
        }

        role.setUniqueId(UUID.randomUUID().toString());
        role.setInitialisation(Initialisation.init());

        Roles saved = repo.save(role);
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), saved.getId(), "Role", "Ajout d'un rôle avec succès !");
        }

        return roleMapper.toDto(saved); // 🔥 PLUS de fromRole
    }

    @Override
    public RoleDTO update(Roles role, String uniqueIdRole) {

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

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), dbRole.getId(), "Roles", "Mise à jour d'un rôle");
        }

        return roleMapper.toDto(updated); 
    }

    @Override
    public String deleteOrRecover(String uniqueIdRole) {

        Roles dbRole = repo.findByUniqueId(uniqueIdRole)
            .orElseThrow(() -> new RuntimeException("Le rôle avec ID '" + uniqueIdRole + "' n'existe pas !"));

        boolean isNowRemoved = !dbRole.getInitialisation().getRemoved();
        dbRole.getInitialisation().setRemoved(isNowRemoved);
        repo.save(dbRole);

        String action = isNowRemoved ? "Suppression" : "Récupération";
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), dbRole.getId(), "Roles", action + " d'un rôle");
        }

        return action + " réussie";
    }

    @Override
    public PaginatedResponse<RoleDTO> findAll(int page, int size, String type) {

        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createAt"));
        Page<Roles> rolePage = Page.empty();

        if (type.equals("folder")) {
            rolePage = repo.findByInitialisationRemovedFalseAndInitialisationArchiveFalse(pageable);
        } else if (type.equals("archive")) {
            rolePage = repo.findByInitialisationRemovedFalseAndInitialisationArchiveTrue(pageable);
        } else if (type.equals("trash")) {
            rolePage = repo.findByInitialisationRemovedTrue(pageable);
        }

        List<RoleDTO> roleDtos = roleMapper.toDtoList(rolePage.getContent()); // 🔥

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

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        if (currentUser != null) {
            logs.addLogs(currentUser.getId(), dbRole.getId(), "Roles", action + " d'un rôle");
        }

        return action + " réussie";
    }

    @Override
    public PaginatedResponse<RoleDTO> search(String search) {

        List<Roles> roles = repo.searchRoles(search.trim());
        List<RoleDTO> dtos = roleMapper.toDtoList(roles); // 🔥 

        return new PaginatedResponse<>(
                dtos,
                1,
                1,
                dtos.size(),
                dtos.size()
        );
    }

}

