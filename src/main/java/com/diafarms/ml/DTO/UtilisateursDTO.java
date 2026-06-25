package com.diafarms.ml.DTO;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.stream.Collectors;

import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
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
public class UtilisateursDTO {
    
    private Long id;
    private String uniqueId;
    private String fullName;
    private String region;
    private String city;
    private String farmName;
    private String photo;
    private String username;
    private String email;
    private String telephone;
    private boolean statut;
    private String password;
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime lastLogin; 
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime updatedAt;
    
    private Set<RoleDTO> roles;


     public static UtilisateursDTO fromEntityList(Utilisateurs utilisateur) {

        if (utilisateur == null) {
            return null;
        }

        return UtilisateursDTO.builder()
                .id(utilisateur.getId())
                .uniqueId(utilisateur.getUniqueId())
                .fullName(utilisateur.getFullName())
                .region(utilisateur.getRegion())
                .city(utilisateur.getCity())
                .photo(utilisateur.getPhoto())
                .farmName(utilisateur.getFarmName())
                .username(utilisateur.getUsername())
                .email(utilisateur.getEmail())
                .telephone(utilisateur.getTelephone())
                .statut(utilisateur.getStatut())
                .createdAt(utilisateur.getInitialisation().getCreatedAt())
                .updatedAt(utilisateur.getInitialisation().getUpdatedAt())
                .lastLogin(utilisateur.getLastLogin())
                .roles(mapToRoleDtos(utilisateur.getRoles()))
                .build();
    }

    /**
     * Construit un DTO depuis l'entité Utilisateurs.
     * 
     * @param utilisateur l'entité persistée
     * @param plainPassword le mot de passe en clair (pour affichage initial)
     * @return le DTO formaté
     */
    public static UtilisateursDTO fromEntity(Utilisateurs utilisateur, String plainPassword) {
        if (utilisateur == null) {
            return null;
        }

        return UtilisateursDTO.builder()
                .id(utilisateur.getId())
                .uniqueId(utilisateur.getUniqueId())
                .fullName(utilisateur.getFullName())
                .region(utilisateur.getRegion())
                .city(utilisateur.getCity())
                .photo(utilisateur.getPhoto())
                .farmName(utilisateur.getFarmName())
                .username(utilisateur.getUsername())
                .email(utilisateur.getEmail())
                .telephone(utilisateur.getTelephone())
                .statut(utilisateur.getStatut())
                .password(plainPassword)
                .createdAt(utilisateur.getInitialisation().getCreatedAt())
                .updatedAt(utilisateur.getInitialisation().getUpdatedAt())
                .lastLogin(utilisateur.getLastLogin())
                .roles(mapToRoleDtos(utilisateur.getRoles()))
                .build();
    }

    /**
     * Version sans mot de passe (pour les requêtes ultérieures).
     */
    public static UtilisateursDTO fromEntity(Utilisateurs utilisateur) {
        return fromEntity(utilisateur, null);
    }

    private static Set<RoleDTO> mapToRoleDtos(Set<Roles> roles) {
        if (roles == null) return null;
        return roles.stream()
                .map(RoleDTO::fromEntity)
                .collect(Collectors.toSet());
    }

    public static UtilisateursDTO fromSelect(Utilisateurs utilisateur) {

        return UtilisateursDTO.builder()
                .id(utilisateur.getId())
                .uniqueId(utilisateur.getUniqueId())
                .fullName(utilisateur.getFullName())
                .build();
    }
}



