package com.diafarms.ml.ServiceImpl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.UsersAuth_DTO;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.services.AuthServices;



@Service
public class AuthImpl implements AuthServices {

    private final UtilisateursRepo repo;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;

    @Autowired
    public AuthImpl(
            JwtEncoder jwtEncoder,
            JwtDecoder jwtDecoder,
            AuthenticationManager authenticationManager,
            UserDetailsService userDetailsService,
            UtilisateursRepo repo
    ) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.repo = repo;
    }

    @Override
    public ResponseEntity<Object> jwt(String grantType, String identifiant, String password,
                                      boolean ouiRefresh, String refreshToken) {

        String subject = null;
        String scope = null;

        // =============================== LOGIN NORMAL ===============================
        if (grantType.equals("password")) {

            Authentication authentication;
            try {
                authentication = authenticationManager.authenticate(
                        new UsernamePasswordAuthenticationToken(identifiant, password)
                );
            } catch (Exception e) {
                return new ResponseEntity<>(Map.of("errorMessage", "Identifiant ou mot de passe incorrect"),
                        HttpStatus.UNAUTHORIZED);
            }

            subject = authentication.getName(); // username
            scope = authentication.getAuthorities()
                    .stream().map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(" "));
        }

        // =============================== REFRESH TOKEN ===============================
        else if (grantType.equals("refreshToken")) {

            if (refreshToken == null) {
                return new ResponseEntity<>(Map.of("errorMessage","Refresh Token is required"),
                        HttpStatus.UNAUTHORIZED);
            }

            Jwt decodeJWT;
            try {
                decodeJWT = jwtDecoder.decode(refreshToken);
            } catch (JwtException e) {
                return new ResponseEntity<>(Map.of("errorMessage", e.getMessage()),
                        HttpStatus.UNAUTHORIZED);
            }

            subject = decodeJWT.getSubject(); // username
            UserDetails userDetails = userDetailsService.loadUserByUsername(subject);

            scope = userDetails.getAuthorities()
                    .stream().map(GrantedAuthority::getAuthority)
                    .collect(Collectors.joining(" "));
        }

        // =============================== ON RÉCUPÈRE LE USER ===============================
        Utilisateurs currentUser = repo.findByEmailOrUsernameOrTelephoneAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                identifiant, identifiant, identifiant
        ).orElseThrow(() -> new IllegalArgumentException("Identifiant incorrect"));

        // =============================== CREATION DU JWT ===============================
        Instant now = Instant.now();

        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .issuer("diafarms")
                .claim("scope", scope)
                .claim("uniqueId", currentUser.getUniqueId())
                .build();

        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(jwtClaimsSet)).getTokenValue();

        UsersAuth_DTO authModel = new UsersAuth_DTO();
        authModel.setId(currentUser.getId());
        authModel.setUniqueId(currentUser.getUniqueId());
        authModel.setNom(currentUser.getFullName());
        authModel.setEmail(currentUser.getEmail());
        authModel.setUsername(currentUser.getUsername());
        authModel.setRoles(currentUser.getRoles());
        authModel.setAccessToken(accessToken);

        if (ouiRefresh) {
            JwtClaimsSet refreshClaims = JwtClaimsSet.builder()
                    .subject(subject)
                    .issuedAt(now)
                    .expiresAt(now.plus(7, ChronoUnit.DAYS))
                    .issuer("diafarms")
                    .build();
            String refreshTk = jwtEncoder.encode(JwtEncoderParameters.from(refreshClaims)).getTokenValue();
            authModel.setRefreshToken(refreshTk);
        }

        return new ResponseEntity<>(authModel, HttpStatus.OK);
    }
}
