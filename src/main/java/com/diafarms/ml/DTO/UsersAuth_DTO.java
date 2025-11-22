package com.diafarms.ml.DTO;
import java.util.Set;


import com.diafarms.ml.models.Roles;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter

public class UsersAuth_DTO {
    
    Long id;
    String nom;
    String email;
    String photo;
    String username;
    String uniqueId;
    Set<Roles> roles;
    String refreshToken;
    String accessToken;

}