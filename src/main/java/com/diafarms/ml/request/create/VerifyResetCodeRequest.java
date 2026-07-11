package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class VerifyResetCodeRequest {
    private String email;
    private String code;
}
