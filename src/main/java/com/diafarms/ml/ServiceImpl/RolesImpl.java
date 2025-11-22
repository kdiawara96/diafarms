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

    @Override
    public RoleDto create(Roles role) {
    
        if (repo.findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(role.getRole().toUpperCase()) != null) {
            throw new RuntimeException("Le rôle existe dejà !");
        }
        role.setUniqueId(Initialisation.generateUniqueId());
        role.setInitialisation(Initialisation.init());
        Roles rol = repo.save(role);
        logs.addLogs(rol.getId(), "Roles", "Ajout d'un role avec success!");
       return RoleDto.fromRole(rol);
    }

    @Override
    public RoleDto update(Roles role, String uniqueIdRole) {
       Roles dbRole = repo.findByUniqueId(uniqueIdRole)
        .orElseThrow(() -> new RuntimeException("Le rôle avec ID '" + uniqueIdRole + "' n'existe pas !"));

        // Vérifier si un autre rôle avec le même nom existe déjà
        Roles existingRole = repo.findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(role.getRole().toUpperCase());
        if (existingRole != null && !existingRole.getUniqueId().equals(uniqueIdRole)) {
            throw new RuntimeException("Le rôle existe déjà !");
        }

        dbRole.setRole(role.getRole().toUpperCase()); // on normalise en majuscule si besoin
        Roles updatedRole = repo.save(dbRole);

        logs.addLogs(dbRole.getId(), "Roles", "Mise à jour d'un rôle");

        return RoleDto.fromRole(updatedRole);
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
        // Récupération d'une page via Spring Data
        Page<Roles> rolePage = new PageImpl<>(Collections.emptyList());
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "initialisation.createAt"));

        if (type.equals("folder")) {
            rolePage =  repo.findByInitialisationRemovedFalseAndInitialisationArchiveFalse(pageable);
        } else if(type.equals("archive")) {
            rolePage = repo.findByInitialisationRemovedFalseAndInitialisationArchiveTrue(pageable);
        }else if(type.equals("trash")) {
            rolePage = repo.findByInitialisationRemovedTrue(pageable);
        }

        // Transformation des entités en DTOs
        List<RoleDto> roleDtos = rolePage.getContent()
                .stream()
                .map(RoleDto::fromRole)
                .toList();

        // Construction explicite de PaginatedResponse
        return new PaginatedResponse<>(
                roleDtos,                     // Liste des DTOs
                page,                         // Page actuelle
                rolePage.getTotalPages(),     // Total de pages
                rolePage.getTotalElements(),  // Total d'éléments
                size                          // Taille de la page
        );
    }

    @Override
    public List<RolesSelect> roleSelect() {
        List<Roles> dto = repo.findAllByInitialisationRemovedFalseAndInitialisationArchiveFalse();
        return dto
        .stream()
        .map(RolesSelect::getLitRole).toList();
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
            // Récupération des propriétaires filtrés via le repository
            List<Roles> roles = repo.searchRoles(search.trim());

            // Transformation des entités en DTOs
            List<RoleDto> rol = roles.stream()
                    .map(RoleDto::fromRole)
                    .collect(Collectors.toList());
    
            // Construction explicite de PaginatedResponse avec des valeurs fictives pour la pagination
            return new PaginatedResponse<>(
                    rol,                     // Liste des DTOs
                    1,                                    // Page actuelle (vous pouvez le mettre à 1 si vous voulez une page unique)
                    1,                                    // Total de pages (une seule page puisque vous n'avez pas de pagination réelle)
                    rol.size(),              // Total d'éléments (taille de la liste obtenue)
                    rol.size()               // Taille de la page (ici vous pouvez mettre la taille totale des résultats ou une valeur fixe)
            );
    }

}

