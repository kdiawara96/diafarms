package com.diafarms.ml.request;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdatePassResquest {
    private String uniqueId;
    private String oldPassword;
    private String password;
}