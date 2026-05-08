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
    String fullName;
    String email;
    String photo;
    String username;
    String telephone;
    String FarmName;
    String region;
    String city;
    String uniqueId;
    Set<Roles> roles;
    String refreshToken;
    String accessToken;

}