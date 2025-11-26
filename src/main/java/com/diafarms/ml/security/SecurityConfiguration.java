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
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
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
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
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
        public AuthenticationManager authenticationManager() {
            var authProvider = new DaoAuthenticationProvider();
            authProvider.setPasswordEncoder(passwordEncoder);
            authProvider.setUserDetailsService(userDetailsService);
            return new ProviderManager(authProvider);
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
        @Bean
        @Order(1)
        public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
            http
                .securityMatcher(
                    "/swagger-ui/**",
                    "/v3/api-docs/**",
                    "/templates/**",
                    // "/excel/**",
                    "/diafarms/files/**",
                    "/webjars/**",
                    "/swagger-resources/**",
                    "/api-docs/**"
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
                    .requestMatchers(
                        "/diafarms/api/v1/auth/**",
                        "/diafarms/api/v1/test"
                    ).permitAll()
                    .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

            return httpSecurity.build();
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource(){
            CorsConfiguration configuration = new CorsConfiguration();
            
            configuration.setAllowedOrigins(List.of("http://localhost:3000","https://batimanager.mediphax.com"));
            configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE"));
            configuration.setAllowCredentials(true);  // Autoriser les cookies et les credentials
            configuration.addAllowedHeader("*");
            
            UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
            source.registerCorsConfiguration("/**", configuration);
            
            return source;
        }

    @Bean
    JwtDecoder jwtDecoder() {
        return NimbusJwtDecoder.withPublicKey(rsakeysConfig_.publicKey()).build();
    }

    @Bean
    JwtEncoder jwtEncoder() {
        JWK jwk = new RSAKey.Builder(rsakeysConfig_.publicKey()).privateKey(rsakeysConfig_.privateKey()).build();
        JWKSource<SecurityContext> jwkSource = new ImmutableJWKSet<>(new JWKSet(jwk));
        return new NimbusJwtEncoder(jwkSource);
    }

}
