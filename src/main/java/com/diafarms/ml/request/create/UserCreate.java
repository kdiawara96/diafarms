package com.diafarms.ml.request.create;

import java.util.List;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter

public class UserCreate {
    private String fullName;
    private String region;
    private String city;
    private String farmName;
    private String email;
    private String telephone;
    private List<String> roles; 

}
