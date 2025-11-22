package com.diafarms.ml.request;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter

public class UserRequest {
    private String fullName;
    private String email;
    private String password;
    private String username;
    private String telephone;
    private List<String> roles; 

}
