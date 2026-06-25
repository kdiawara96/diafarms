package com.diafarms.ml.request.update;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class UserUpdate {
    private String fullName;
    private String telephone;
    private String email;
    private String city;
    private String region;
    private List<String> roles; // Reçoit la liste des noms de rôles modifiés (ex: ["PRODUCTION", "FINANCE"])
}