package com.diafarms.ml.DTO;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.diafarms.ml.models.Utilisateurs;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class UtilisateursDto {
    
    private Long id;
    private String fullName;
    private String uniqueId;
    private String username;
    private String email;
    private String telephone;
    private boolean statut;
    private String password;
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime updatedAt;
    private List<RoleDto> roles;

    public static UtilisateursDto fromUser(Utilisateurs user) {

        if (user.getId() == null) return null;

        UtilisateursDto utilisateursDto = new UtilisateursDto();

        utilisateursDto.setId(user.getId());
        utilisateursDto.setUniqueId(user.getUniqueId());
        utilisateursDto.setUsername(user.getUsername());
        utilisateursDto.setEmail(user.getEmail());
        utilisateursDto.setFullName(user.getFullName());
        utilisateursDto.setTelephone(user.getTelephone());
        utilisateursDto.setCreatedAt(user.getInitialisation().getCreatedAt());
        utilisateursDto.setUpdatedAt(user.getInitialisation().getUpdatedAt());
        utilisateursDto.setStatut(user.getStatut());
        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            List<RoleDto> roleDtos = user.getRoles().stream()
                    .map(RoleDto::fromRole)
                    .sorted(Comparator.comparing(RoleDto::getRole))
                    .collect(Collectors.toList());
            utilisateursDto.setRoles(roleDtos);
        } else {
            utilisateursDto.setRoles(Collections.emptyList());
        }

        return utilisateursDto;
    }

  }

