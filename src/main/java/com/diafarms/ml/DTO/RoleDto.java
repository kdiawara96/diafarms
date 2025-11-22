package com.diafarms.ml.DTO;

import java.time.LocalDateTime;
import java.util.List;

import com.diafarms.ml.models.Roles;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class RoleDto {
    private Long id;
    private String uniqueId;
    private String role;  
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime createdAt;
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    private LocalDateTime updatedAt;


    public static RoleDto fromRole(Roles role){

        RoleDto rle = new RoleDto();

        rle.setId(role.getId());
        rle.setUniqueId(role.getUniqueId());
        rle.setCreatedAt(role.getInitialisation().getCreatedAt());
        rle.setRole(role.getRole());

        return rle;
    }
}
