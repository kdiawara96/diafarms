package com.diafarms.ml.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.UtilisateursRepo;

import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collection;


@Service
@RequiredArgsConstructor
//Cette annotation nous permettra d'utiliser les logs pour verifier tous les mouvements de user
@Slf4j
public class UsersDetailService implements UserDetailsService {

    
    private final UtilisateursRepo repo;

    @Override
    @Transactional
    public UserDetails loadUserByUsername(String identifier) throws UsernameNotFoundException {

        if (identifier.trim().isEmpty()){
            throw new UsernameNotFoundException("L'identifiant ou le mot de passe incorrect!");
        }
        //Nous allons recuperer le user par son nom
        Utilisateurs utilisateurs =  repo.findByEmailOrUsernameOrTelephoneAndInitialisationRemovedFalseAndInitialisationArchiveFalse(
                    identifier, identifier, identifier)
            .orElseThrow(() -> new IllegalArgumentException(
                    "Aucun utilisateur trouvé avec cet identifiant: " + identifier));

        Collection<SimpleGrantedAuthority> authorities = new ArrayList<>();
        utilisateurs.getRoles().forEach(role -> authorities.add(new SimpleGrantedAuthority(role.getRole())));

        // return new User(utilisateurs.getUsername(),utilisateurs.getPassword(),authorities);

        return new CustomUserDetails(
            utilisateurs.getUniqueId(), // ID unique
            utilisateurs.getUsername(),
            utilisateurs.getPassword(),
            authorities
        );
    }
}
