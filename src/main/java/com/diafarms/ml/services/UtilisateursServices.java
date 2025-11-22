package com.diafarms.ml.services;


import com.diafarms.ml.DTO.UtilisateursDto;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.request.OTP_Request;
import com.diafarms.ml.request.UpdatePassResquest;
import com.diafarms.ml.request.UserRequest;

public interface UtilisateursServices {
    
    UtilisateursDto save(UserRequest data);
    UtilisateursDto update(String user_unique_id, UserRequest data);
    String delete(String uniqueId);
    String archive(String uniqueId);
    PaginatedResponse<UtilisateursDto> findAll(int page, int size, String type);
    Utilisateurs readByUserName(String username);
    Utilisateurs readByUsernameOrEmail(String usernameOrEmail);
    Utilisateurs readByUsernameOrEmailOrPhone(String usernameOrEmailOrPhone);
    String changeStatut(String uniqueIdUtilisateur);
    String sendCodeForChangePassword(String email);
    String verifyCodeOtpEnvoyeParMail(OTP_Request otp);
    boolean verificationDesInformations(String email, String telephone);
    String updatePassword(UpdatePassResquest data);
    PaginatedResponse<UtilisateursDto> search(String search);
}
