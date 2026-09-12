package com.diafarms.ml.security;

import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.services.UtilisateursServices;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;


@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@Slf4j
public class SecurityConfiguration {

        private RsakeysConfig rsakeysConfig_;
        private PasswordEncoder passwordEncoder;
        private UserDetailsService userDetailsService;
        private UtilisateursServices utilisateursService;

        public SecurityConfiguration(RsakeysConfig rsakeysConfig_,
                                    PasswordEncoder passwordEncoder, UserDetailsService userDetailsService,
                                    UtilisateursServices utilisateursService
        ) {
            this.rsakeysConfig_ = rsakeysConfig_;
            this.passwordEncoder = passwordEncoder;
            this.userDetailsService = userDetailsService;
            this.utilisateursService = utilisateursService;
        }

        @Bean
        public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
            return config.getAuthenticationManager();
        }

        public UserDetailsService userDetailsService(AuthenticationManagerBuilder auth) throws Exception {

            auth.userDetailsService(new UserDetailsService() {
                @Override
                public UserDetails loadUserByUsername(String usernameOrEmail) throws UsernameNotFoundException {
                    Utilisateurs utilisateurs = utilisateursService.readByUsernameOrEmail(usernameOrEmail);
                    Collection<GrantedAuthority> authorities = new ArrayList<>();
                    utilisateurs.getRoles().forEach(role -> {
                        authorities.add(new SimpleGrantedAuthority(role.getRole()));
                    });
                    return new User(utilisateurs.getUsername(), utilisateurs.getPassword(), authorities);
                }
            });

            return null;
        }

        // === CHAÎNE PUBLIQUE (pas de JWT) ===
        // Un cookie access_token invalide/périmé (ex: session précédente révoquée)
        // envoyé par le navigateur sur ces routes ne doit JAMAIS bloquer la requête :
        // tant qu'elles ne passent pas par oauth2ResourceServer(), aucune tentative
        // de décodage/validation du Bearer token n'a lieu, donc un cookie invalide
        // ne peut plus faire échouer une inscription, une connexion ou une
        // déconnexion (permitAll() seul ne suffit pas : le filtre resource-server
        // de la chaîne privée s'exécute avant l'autorisation et rejette la requête
        // en 401 dès qu'un token présent est invalide, même sur un chemin permitAll).
        @Bean
        @Order(1)
        public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
            http
                .securityMatcher(
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/templates/**",
                    "/diafarms/files/**",
                    "/diafarms/api/v1/farms/*/logo",
                    "/diafarms/api/v1/farms/*/tampon",
                    "/webjars/**",
                    "/swagger-resources/**",
                    "/api-docs/**",
                    "/diafarms/api/v1/auth",
                    "/diafarms/api/v1/auth/logout",
                    "/diafarms/api/v1/auth/forgot-password",
                    "/diafarms/api/v1/auth/verify-reset-code",
                    "/diafarms/api/v1/auth/reset-password",
                    "/diafarms/api/v1/users/create",
                    "/diafarms/api/v1/test"
                )
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());

            return http.build();
        }

        // ===  CHAÎNE PRIVÉE (avec JWT) ===
        @Bean
        @Order(2)
        public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
            httpSecurity
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .authorizeHttpRequests(auth -> auth
                    .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2
                    .bearerTokenResolver(new CookieBearerTokenResolver())
                    .jwt(Customizer.withDefaults())
                );

            return httpSecurity.build();
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource(){
            CorsConfiguration configuration = new CorsConfiguration();
            
            // configuration.setAllowedOrigins(List.of("http://localhost:8080","https://api.diafarms.com"));
            configuration.setAllowedOrigins(List.of(
                "http://localhost:8080",
                "http://192.168.1.40:8080",
                "http://localhost:8081",
                "http://192.168.1.40:8081",
                "https://api.diafarms.com",
                "https://cocorico.batimanager.net"
            ));

            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
            configuration.setAllowCredentials(true);  // Autoriser les cookies et les credentials
            configuration.addAllowedHeader("*");
            
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", configuration);
            
            return source;
        }

    @Bean
    JwtDecoder jwtDecoder(UserStatusJwtValidator userStatusJwtValidator) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(rsakeysConfig_.publicKey()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                userStatusJwtValidator
        ));
        return decoder;
    }

    @Bean
    JwtEncoder jwtEncoder() {
        JWK jwk = new RSAKey.Builder(rsakeysConfig_.publicKey()).privateKey(rsakeysConfig_.privateKey()).build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

}
