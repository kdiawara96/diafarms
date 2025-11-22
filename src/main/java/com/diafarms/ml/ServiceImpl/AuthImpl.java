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
import org.springframework.security.crypto.password.PasswordEncoder;
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
// @RequiredArgsConstructor
public class AuthImpl implements AuthServices{
    
    @Autowired
    private  UtilisateursRepo repo;
    
    @Autowired
    private  PasswordEncoder passwordEncoder;   
    
    private  JwtEncoder jwtEncoder;
    private  JwtDecoder jwtDecoder;
    private  AuthenticationManager authenticationManager;
    private  UserDetailsService userDetailsService;


  public AuthImpl(
    JwtEncoder jwtEncoder, 
    JwtDecoder jwtDecoder,
    AuthenticationManager authenticationManager, 
    UserDetailsService 
    userDetailsService, 
    UtilisateursRepo repo
    ) {
       this.jwtEncoder = jwtEncoder;
       this.jwtDecoder = jwtDecoder;
       this.authenticationManager = authenticationManager;
       this.userDetailsService = userDetailsService;
    }
    @Override
    public ResponseEntity<Object> jwt(String grantType, String email, String password, boolean ouiRefresh, String refreshToken) {
      
        String subject = null;
        String scope = null;
        
        if (grantType.equals("password")){
          
            try {
                userVerify(password, email);
            } catch (Exception e) {
                return new ResponseEntity<>(Map.of("errorMessage",e.getMessage()), HttpStatus.NOT_FOUND);
            }

            Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, password)
            );
            subject= authentication.getName();
            scope = authentication
                    .getAuthorities()
                    .stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors
                    .joining(" "));


        }else if(grantType.equals("google")){

            try {
                userVerify(password, email);
            } catch (Exception e) {
                return new ResponseEntity<>(Map.of("errorMessage",e.getMessage()), HttpStatus.NOT_FOUND);
            }
            
            //NOUS ALLONS VERIFIER L'EXISTANCE DE L'UTILISATEUR AVEC LES INFOS DE GOOGLE
              Authentication authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(email, password));
                    // new UsernamePasswordAuthenticationToken("mediphax@gmail.com", "mediphax"));

            subject= authentication.getName();
            scope = authentication
                    .getAuthorities()
                    .stream()
                    .map(GrantedAuthority::getAuthority)
                    .collect(Collectors
                    .joining(" "));

          } else if(grantType.equals("refreshToken")){

            if (refreshToken == null){
                //Un message si la durée du refresh token à expiré
                return new ResponseEntity<>(Map.of("errorMessage","Refresh Token is requeried"), HttpStatus.UNAUTHORIZED);
            }
            Jwt decodeJWT = null;
            try {
                //quand nous decodons il va verifier s'il n'est pas expirer
                decodeJWT = jwtDecoder.decode(refreshToken);
            } catch (JwtException e) {
                return new ResponseEntity<>(Map.of("errorMessage",e.getMessage()), HttpStatus.UNAUTHORIZED);
            }

            //String subjet = decodeJWT.getSubject();
            subject = decodeJWT.getSubject();
            UserDetails userDetails = userDetailsService.loadUserByUsername(subject);
            Collection<? extends GrantedAuthority> authorities = userDetails.getAuthorities();
            scope = authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.joining(" "));
        }


        UsersAuth_DTO authModel = new UsersAuth_DTO();
        Utilisateurs currentUser = repo.findByEmailAndInitialisationRemovedFalseAndInitialisationArchiveFalse(email);

        if(currentUser != null){
            authModel.setId(currentUser.getId());
            authModel.setUniqueId(currentUser.getUniqueId());
            authModel.setNom(currentUser.getFullName());
            authModel.setRoles(currentUser.getRoles());
            authModel.setUsername(currentUser.getUsername());
            authModel.setEmail(currentUser.getEmail());
        }

        Instant instant = Instant.now();
        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(instant)
                .expiresAt(instant.plus(ouiRefresh?7:7, ChronoUnit.DAYS))
                .issuer("diafarms")
                .claim("scope", scope)
                .claim("uniqueId", currentUser.getUniqueId()) 
                .build();
        String jwtAccesToken = jwtEncoder.encode(JwtEncoderParameters.from(jwtClaimsSet)).getTokenValue();

        authModel.setAccessToken(jwtAccesToken);
        //lol
        if(ouiRefresh){
            JwtClaimsSet jwtClaimsSetRefresh = JwtClaimsSet.builder()
                    .subject(subject)
                    .issuedAt(instant)
                    .expiresAt(instant.plus(7, ChronoUnit.DAYS))
                    .issuer("diafarms")
                    .build();
            String jwtRefreshToken = jwtEncoder.encode(JwtEncoderParameters.from(jwtClaimsSetRefresh)).getTokenValue();
        
            authModel.setRefreshToken(jwtRefreshToken);
        }

        return  new ResponseEntity<>(authModel, HttpStatus.OK);
    }

    ResponseEntity<Object>  userVerify(String password, String email)  {

        Utilisateurs usr = repo.findByEmailAndInitialisationRemovedFalseAndInitialisationArchiveFalse(email);

        if (usr == null || !passwordEncoder.matches(password, usr.getPassword())) {
            throw new NoSuchElementException("L'e-mail ou le mot de passe est incorrect !");
        }
        return null;
    }

    

}
