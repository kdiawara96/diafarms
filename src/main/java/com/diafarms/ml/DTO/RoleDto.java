package com.diafarms.ml.DTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

import com.diafarms.ml.models.Roles;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@Builder
public class RoleDto {
    
    private Long id;
    private String uniqueId;
    private String role;
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime updatedAt;

    /**
     * Construit un RoleDto depuis l'entité Roles.
     * 
     * @param role l'entité role
     * @return le DTO formaté
     */
    public static RoleDto fromEntity(Roles role) {
        if (role == null) {
            return null;
        }

        return RoleDto.builder()
                .id(role.getId())
                .uniqueId(role.getUniqueId())
                .role(role.getRole())
                .createdAt(role.getInitialisation().getCreatedAt())
                .updatedAt(role.getInitialisation().getUpdatedAt())
                .build();
    }

    /**
     * Construit une liste de RoleDto depuis une liste d'entités Roles.
     * 
     * @param roles liste des entités roles
     * @return liste des DTOs formatés
     */
    public static List<RoleDto> fromEntities(List<Roles> roles) {
        if (roles == null) {
            return null;
        }
        return roles.stream()
                .map(RoleDto::fromEntity)
                .collect(Collectors.toList());
    }
}