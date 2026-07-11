package com.diafarms.ml.request.update;

import lombok.Data;

@Data
public class ResetPasswordRequest {
    private String email;
    private String code;
    private String newPassword;
}
