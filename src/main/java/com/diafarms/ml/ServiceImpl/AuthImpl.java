package com.diafarms.ml.ServiceImpl;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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
import com.diafarms.ml.commons.AppAccessRules;
import com.diafarms.ml.models.FarmAppSettings;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.FarmAppSettingsRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.services.AuthServices;



@Service
public class AuthImpl implements AuthServices {

    private final UtilisateursRepo repo;
    private final FarmAppSettingsRepo farmAppSettingsRepo;
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
            UtilisateursRepo repo,
            FarmAppSettingsRepo farmAppSettingsRepo
    ) {
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
        this.authenticationManager = authenticationManager;
        this.userDetailsService = userDetailsService;
        this.repo = repo;
        this.farmAppSettingsRepo = farmAppSettingsRepo;
    }

    @Override
    public ResponseEntity<Object> jwt(String grantType, String identifiant, String password,
                                      boolean ouiRefresh, String refreshToken, String clientType) {

        String subject = null;
        String scope = null;
        // tokenVersion embarqué dans le refresh token décodé (null pour un login mot
        // de passe classique, comparé plus bas à celui en base uniquement pour un
        // refresh — voir Utilisateurs.tokenVersion).
        Integer refreshTokenVersion = null;

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
            refreshTokenVersion = decodeJWT.getClaim("tokenVersion");
        }

        // =============================== ON RÉCUPÈRE LE USER ===============================
        // Pour un refresh, "identifiant" n'est pas renseigné par l'appelant (le web ne
        // connaît que le refresh token, pas l'identifiant de session) — on retrouve
        // l'utilisateur par le "subject" décodé du refresh token lui-même, jamais par
        // le paramètre "identifiant" dans ce cas.
        String cleIdentification = grantType.equals("refreshToken") ? subject : identifiant;
        Utilisateurs currentUser = repo.findByEmailOrUsernameOrTelephoneAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                cleIdentification, cleIdentification, cleIdentification
        ).orElseThrow(() -> new IllegalArgumentException("Identifiant incorrect"));

        // Refresh token émis avant la dernière déconnexion ("Déconnexion" incrémente
        // tokenVersion, voir authControllers.logout) : rejeté plutôt que de laisser
        // une session soi-disant terminée continuer à générer de nouveaux access
        // tokens valables jusqu'à 7 jours.
        if (grantType.equals("refreshToken")) {
            int versionEnBase = currentUser.getTokenVersion() != null ? currentUser.getTokenVersion() : 0;
            int versionDuToken = refreshTokenVersion != null ? refreshTokenVersion : 0;
            if (versionDuToken != versionEnBase) {
                return new ResponseEntity<>(Map.of("errorMessage", "Session expirée, veuillez vous reconnecter."),
                        HttpStatus.UNAUTHORIZED);
            }
        }

        // =============================== ACCÈS APP (PRODUCTEUR/FINANCIER) ===============================
        // Ne s'applique qu'au login mot de passe (web ou mobile), pas au refresh d'une
        // session déjà en cours — voir UserStatusJwtValidator pour la revalidation
        // continue des tokens issus d'un QR (mobile), qui elle s'applique à chaque requête.
        if (grantType.equals("password") && currentUser.getFarm() != null) {
            java.util.Set<String> roles = currentUser.getRoles() == null ? java.util.Set.of()
                    : currentUser.getRoles().stream().map(r -> r.getRole()).collect(Collectors.toSet());
            FarmAppSettings settings = farmAppSettingsRepo.findByFarm_Id(currentUser.getFarm().getId()).orElse(null);
            boolean isMobile = "mobile".equalsIgnoreCase(clientType);
            boolean allowed = isMobile ? AppAccessRules.canAccessMobile(settings, roles) : AppAccessRules.canAccessWeb(settings, roles);
            if (!allowed) {
                String message = isMobile
                        ? "L'accès à l'application mobile n'est pas activé pour votre rôle. Contactez votre administrateur."
                        : "L'accès à l'application web n'est pas activé pour votre rôle. Contactez votre administrateur.";
                return new ResponseEntity<>(Map.of("errorMessage", message), HttpStatus.FORBIDDEN);
            }
        }

        // =============================== CREATION DU JWT ===============================
        Instant now = Instant.now();

        int tokenVersionActuel = currentUser.getTokenVersion() != null ? currentUser.getTokenVersion() : 0;

        JwtClaimsSet jwtClaimsSet = JwtClaimsSet.builder()
                .subject(subject)
                .issuedAt(now)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .issuer("diafarms")
                .claim("scope", scope)
                .claim("uniqueId", currentUser.getUniqueId())
                .claim("tokenVersion", tokenVersionActuel)
                .build();

        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(jwtClaimsSet)).getTokenValue();

        UsersAuth_DTO authModel = new UsersAuth_DTO();
        authModel.setId(currentUser.getId());
        authModel.setUniqueId(currentUser.getUniqueId());
        authModel.setFullName(currentUser.getFullName());
        authModel.setTelephone(currentUser.getTelephone());
        authModel.setFarmName(currentUser.getFarmName());
        authModel.setRegion(currentUser.getRegion());
        authModel.setCity(currentUser.getCity());
        authModel.setPhoto(currentUser.getPhoto());
        authModel.setEmail(currentUser.getEmail());
        authModel.setUsername(currentUser.getUsername());
        authModel.setRoles(currentUser.getRoles());
        authModel.setAccessToken(accessToken);
        authModel.setMustChangePassword(Boolean.TRUE.equals(currentUser.getMustChangePassword()));

        if (ouiRefresh) {
            JwtClaimsSet refreshClaims = JwtClaimsSet.builder()
                    .subject(subject)
                    .issuedAt(now)
                    .expiresAt(now.plus(7, ChronoUnit.DAYS))
                    .issuer("diafarms")
                    .claim("tokenVersion", tokenVersionActuel)
                    .build();
            String refreshTk = jwtEncoder.encode(JwtEncoderParameters.from(refreshClaims)).getTokenValue();
            authModel.setRefreshToken(refreshTk);
        }

        return new ResponseEntity<>(authModel, HttpStatus.OK);
    }
}
