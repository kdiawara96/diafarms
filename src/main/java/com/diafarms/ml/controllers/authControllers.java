package com.diafarms.ml.controllers;

import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


import com.diafarms.ml.DTO.UsersAuth_DTO;
import com.diafarms.ml.DTO.UtilisateursDTO;
import com.diafarms.ml.commons.SecurityUtils;
import com.diafarms.ml.others.ApiResponse;
import com.diafarms.ml.request.create.ForgotPasswordRequest;
import com.diafarms.ml.request.create.VerifyResetCodeRequest;
import com.diafarms.ml.request.update.ResetPasswordRequest;
import com.diafarms.ml.security.CookieAuthUtils;
import com.diafarms.ml.services.AuthServices;
import com.diafarms.ml.services.PasswordResetService;
import com.diafarms.ml.services.UtilisateursServices;

import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.RequestBody;

@RestController
@RequestMapping("/diafarms/api/v1")
@RequiredArgsConstructor
public class authControllers {

    private static final long ACCESS_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;
    private static final long REFRESH_TOKEN_TTL_SECONDS = 7 * 24 * 60 * 60;

    private final AuthServices serives;
    private final CookieAuthUtils cookieAuthUtils;
    private final UtilisateursServices utilisateursServices;
    private final PasswordResetService passwordResetService;


  @PostMapping("/auth")
    public ResponseEntity<ApiResponse<Object>> auth(
        @RequestParam("grantType") String grantType,
        @RequestParam("identifiant") String identifiant,
        @RequestParam("password") String password,
        @RequestParam(value = "ouiRefresh", defaultValue = "false") boolean ouiRefresh,
        @RequestParam(value = "refreshToken", required = false) String refreshToken,
        @RequestHeader(value = "X-Client-Type", required = false) String clientType,
        HttpServletResponse httpServletResponse){
        try {
            ResponseEntity<Object> result = serives.jwt(grantType, identifiant, password, ouiRefresh, refreshToken, clientType);
            Object body = result.getBody();
            boolean isMobileClient = "mobile".equalsIgnoreCase(clientType);

            if (body instanceof UsersAuth_DTO authModel && authModel.getAccessToken() != null) {
                cookieAuthUtils.setAccessCookie(httpServletResponse, authModel.getAccessToken(), ACCESS_TOKEN_TTL_SECONDS);
                if (authModel.getRefreshToken() != null) {
                    cookieAuthUtils.setRefreshCookie(httpServletResponse, authModel.getRefreshToken(), REFRESH_TOKEN_TTL_SECONDS);
                }
                // Le web s'appuie uniquement sur le cookie HttpOnly (le token ne doit jamais
                // atteindre le JS, pour limiter l'impact d'une XSS). Un client mobile natif n'a
                // pas de jar de cookies pratique : on lui laisse le token dans le corps JSON.
                if (!isMobileClient) {
                    authModel.setAccessToken(null);
                    authModel.setRefreshToken(null);
                }
            }

            // Message et status réels : auparavant le status était toujours écrasé par
            // HttpStatus.OK et le message toujours "Opération réussie", même en cas
            // d'identifiant/mot de passe incorrect (AuthImpl renvoie alors un Map
            // {errorMessage: ...} avec un status 401 qui n'était jamais propagé).
            String message = "Opération réussie";
            if (body instanceof java.util.Map<?, ?> errorBody && errorBody.get("errorMessage") != null) {
                message = String.valueOf(errorBody.get("errorMessage"));
            }
            return ApiResponse.createResponse(message, HttpStatus.valueOf(result.getStatusCode().value()), body, null);
        } catch ( NoSuchElementException e) {
            // Gestion des erreurs de validation
            List<String> errors = Arrays.asList(e.getMessage());
            return ApiResponse.createResponse("Erreur de validation", HttpStatus.BAD_REQUEST, null, errors);
        } catch ( IllegalArgumentException e) {
            // Gestion des erreurs de validation
            List<String> errors = Arrays.asList(e.getMessage());
            return ApiResponse.createResponse("La données est invalide ou manquante pour la sauvegarde", HttpStatus.BAD_REQUEST, null, errors);
        }  catch (Exception e) {
            // Gestion des autres exceptions
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponse<String>> logout(HttpServletResponse httpServletResponse) {
        cookieAuthUtils.clearAuthCookies(httpServletResponse);
        return ApiResponse.createResponse("Déconnexion réussie", HttpStatus.OK, "OK", null);
    }

    @GetMapping("/auth/me")
    public ResponseEntity<ApiResponse<UtilisateursDTO>> me() {
        String uniqueId = SecurityUtils.getCurrentUserUniqueId();
        return ApiResponse.createResponse("Opération réussie", HttpStatus.OK, utilisateursServices.getUtilisateurByUniqueId(uniqueId), null);
    }

    // Toujours le même message de succès, que l'email existe ou non, pour ne
    // pas révéler quels emails sont enregistrés dans l'application.
    @PostMapping("/auth/forgot-password")
    public ResponseEntity<ApiResponse<String>> forgotPassword(@RequestBody ForgotPasswordRequest request) {
        try {
            passwordResetService.requestReset(request.getEmail());
        } catch (Exception e) {
            // Ne jamais renvoyer l'échec au client : même comportement observable
            // qu'un email inconnu.
        }
        return ApiResponse.createResponse(
            "Si cet email est associé à un compte, un code de vérification vient d'être envoyé.",
            HttpStatus.OK, "OK", null
        );
    }

    @PostMapping("/auth/verify-reset-code")
    public ResponseEntity<ApiResponse<String>> verifyResetCode(@RequestBody VerifyResetCodeRequest request) {
        boolean valid = passwordResetService.verifyCode(request.getEmail(), request.getCode());
        if (!valid) {
            return ApiResponse.createResponse("Code invalide ou expiré.", HttpStatus.BAD_REQUEST, null, List.of("Code invalide ou expiré."));
        }
        return ApiResponse.createResponse("Code valide", HttpStatus.OK, "OK", null);
    }

    @PostMapping("/auth/reset-password")
    public ResponseEntity<ApiResponse<String>> resetPassword(@RequestBody ResetPasswordRequest request) {
        try {
            passwordResetService.resetPassword(request.getEmail(), request.getCode(), request.getNewPassword());
            return ApiResponse.createResponse("Mot de passe réinitialisé avec succès", HttpStatus.OK, "OK", null);
        } catch (IllegalArgumentException e) {
            return ApiResponse.createResponse("Données invalides", HttpStatus.BAD_REQUEST, null, List.of(e.getMessage()));
        } catch (Exception e) {
            return ApiResponse.createResponse("Erreur interne du serveur", HttpStatus.INTERNAL_SERVER_ERROR, null, null);
        }
    }

}
