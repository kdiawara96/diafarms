package com.diafarms.ml.ServiceImpl;


import lombok.RequiredArgsConstructor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;


import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.UtilisateursDto;
import com.diafarms.ml.DTO.mappers.UtilisateurMapper;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.RolesRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.OTP_Request;
import com.diafarms.ml.request.UpdatePassResquest;
import com.diafarms.ml.request.UserRequest;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.UtilisateursServices;



@Service
@RequiredArgsConstructor
public class UtilisateurImpl implements UtilisateursServices {
    
    
    private final UtilisateursRepo utilisateursRepo;
    private final UtilisateurMapper utilisateurMapper;
    private final PasswordEncoder encoder;
    private final LogsServices logsServices;
    private final RolesRepo roleRepo;

    
    
    @Override
    public UtilisateursDto save(UserRequest data) {
        // =================== Vérifications ===================
        if (utilisateursRepo.existsByUsername(data.getUsername())) {
            throw new IllegalArgumentException("Le nom d'utilisateur '" + data.getUsername() + "' existe déjà.");
        }
        if (utilisateursRepo.existsByEmail(data.getEmail())) {
            throw new IllegalArgumentException("L'email '" + data.getEmail() + "' existe déjà.");
        }
        if (utilisateursRepo.existsByTelephone(data.getTelephone())) {
            throw new IllegalArgumentException("Le numéro de téléphone '" + data.getTelephone() + "' existe déjà.");
        }

        // =================== Création utilisateur ===================
        Utilisateurs user = new Utilisateurs();
        user.setFullName(data.getFullName());
        user.setUsername(data.getUsername());
        user.setEmail(data.getEmail());
        user.setTelephone(data.getTelephone());
        user.setPassword(encoder.encode(data.getPassword()));
        user.setUniqueId(UUID.randomUUID().toString());
        user.setInitialisation(Initialisation.init());

        // =================== Gestion des roles ===================
        Set<Roles> rolesToAdd = new HashSet<>();
        if (data.getRoles() != null) { // utiliser getRole()
            for (String roleUniqueId : data.getRoles()) {
                Roles role = roleRepo.findByUniqueId(roleUniqueId)
                        .orElseThrow(() -> new IllegalArgumentException("Le rôle avec ID '" + roleUniqueId + "' n'existe pas."));
                rolesToAdd.add(role);
            }
        }
        user.setRoles(rolesToAdd);


        // =================== Sauvegarde ===================
        utilisateursRepo.save(user);

        // =================== Logs ===================
        logsServices.addLogs(user.getId(), "UtilisateurImpl", "Création de l'utilisateur");

        return utilisateurMapper.toDto(user);
    }


   @Override
    public UtilisateursDto update(String user_unique_id, UserRequest data) {
        Utilisateurs user = utilisateursRepo.findByUniqueId(user_unique_id)
                .orElseThrow(() -> new RuntimeException("Utilisateur avec ID '" + user_unique_id + "' non trouvé."));

        // =================== Vérification doublons ===================
        if (!user.getUsername().equals(data.getUsername()) && utilisateursRepo.existsByUsername(data.getUsername())) {
            throw new IllegalArgumentException("Le nom d'utilisateur '" + data.getUsername() + "' existe déjà.");
        }
        if (!user.getEmail().equals(data.getEmail()) && utilisateursRepo.existsByEmail(data.getEmail())) {
            throw new IllegalArgumentException("L'email '" + data.getEmail() + "' existe déjà.");
        }
        if (!user.getTelephone().equals(data.getTelephone()) && utilisateursRepo.existsByTelephone(data.getTelephone())) {
            throw new IllegalArgumentException("Le numéro de téléphone '" + data.getTelephone() + "' existe déjà.");
        }

        // =================== Mise à jour ===================
        user.setFullName(data.getFullName());
        user.setUsername(data.getUsername());
        user.setEmail(data.getEmail());
        user.setTelephone(data.getTelephone());
        if (data.getPassword() != null && !data.getPassword().isEmpty()) {
            user.setPassword(encoder.encode(data.getPassword()));
        }

        if (user.getInitialisation() == null) {
            user.setInitialisation(Initialisation.init());
        } else {
            Initialisation.updateDate(user.getInitialisation());
        }

        // =================== Gestion des roles ===================
        if (data.getRoles() != null) {
            Set<Roles> rolesToAdd = new HashSet<>();
            for (String roleUniqueId : data.getRoles()) {
                Roles role = roleRepo.findByUniqueId(roleUniqueId)
                        .orElseThrow(() -> new IllegalArgumentException("Le rôle avec ID '" + roleUniqueId + "' n'existe pas."));
                rolesToAdd.add(role);
            }
            user.setRoles(rolesToAdd);
        }

        utilisateursRepo.save(user);

        // =================== Logs ===================
        logsServices.addLogs(user.getId(), "UtilisateurImpl", "Mise à jour de l'utilisateur");

        return utilisateurMapper.toDto(user);
    }

   @Override
public String delete(String uniqueId) {
    Utilisateurs user = utilisateursRepo.findByUniqueId(uniqueId)
            .orElseThrow(() -> new RuntimeException("Utilisateur avec ID '" + uniqueId + "' non trouvé."));

    utilisateursRepo.delete(user);

    // Logs
    logsServices.addLogs(user.getId(), "UtilisateurImpl", "Suppression de l'utilisateur");

    return "Utilisateur supprimé avec succès";
}

@Override
public String archive(String uniqueId) {
    Utilisateurs user = utilisateursRepo.findByUniqueId(uniqueId)
            .orElseThrow(() -> new RuntimeException("Utilisateur avec ID '" + uniqueId + "' non trouvé."));

    if (user.getInitialisation() == null) {
        user.setInitialisation(Initialisation.init());
    }
    user.getInitialisation().setArchive(true);

    utilisateursRepo.save(user);

    // Logs
    logsServices.addLogs(user.getId(), "UtilisateurImpl", "Archivage de l'utilisateur");

    return "Utilisateur archivé avec succès";
}

    @Override
    public PaginatedResponse<UtilisateursDto> findAll(int page, int size, String type) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("initialisation.createdAt").descending());
        Page<Utilisateurs> usersPage;

        switch (type != null ? type.toLowerCase() : "") {
            case "archived":
                usersPage = utilisateursRepo.findByInitialisationArchiveTrue(pageable);
                break;
            case "active":
                usersPage = utilisateursRepo.findByInitialisationArchiveFalse(pageable);
                break;
            default:
                usersPage = utilisateursRepo.findAll(pageable);
                break;
        }

        List<UtilisateursDto> dtos = utilisateurMapper.toDtoList(usersPage.getContent());

        return new PaginatedResponse<>(
                dtos,
                usersPage.getNumber(),
                usersPage.getSize(),
                usersPage.getTotalElements(),
                usersPage.getTotalPages()
        );
    }


    @Override
    public Utilisateurs readByUsernameOrEmailOrPhone(String usernameOrEmailOrPhone) {
        return utilisateursRepo.findByUsernameOrEmailOrTelephone(usernameOrEmailOrPhone,
                                                                usernameOrEmailOrPhone,
                                                                usernameOrEmailOrPhone)
                .orElseThrow(() -> new RuntimeException(
                        "Aucun utilisateur trouvé avec le nom d'utilisateur, l'email ou le téléphone : '" 
                        + usernameOrEmailOrPhone + "'"
                ));
    }

    @Override
    public Utilisateurs readByUserName(String username) {
        Utilisateurs user = utilisateursRepo.findByUsername(username);
        if (user == null) {
            throw new RuntimeException("Aucun utilisateur trouvé avec le nom d'utilisateur : '" + username + "'");
        }
        return user;
    }

    @Override
    public Utilisateurs readByUsernameOrEmail(String usernameOrEmail) {
        Utilisateurs user = utilisateursRepo.findByUsernameOrEmailOrTelephone(usernameOrEmail,
                                                                            usernameOrEmail,
                                                                            usernameOrEmail)
                .orElseThrow(() -> new RuntimeException(
                        "Aucun utilisateur trouvé avec le nom d'utilisateur ou l'email : '" + usernameOrEmail + "'"
                ));
        return user;
    }


    @Override
    public String changeStatut(String uniqueIdUtilisateur) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'changeStatut'");
    }

    @Override
    public String sendCodeForChangePassword(String email) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'sendCodeForChangePassword'");
    }

    @Override
    public String verifyCodeOtpEnvoyeParMail(OTP_Request otp) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'verifyCodeOtpEnvoyeParMail'");
    }

    @Override
    public boolean verificationDesInformations(String email, String telephone) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'verificationDesInformations'");
    }

    @Override
    public String updatePassword(UpdatePassResquest data) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'updatePassword'");
    }

    @Override
    public PaginatedResponse<UtilisateursDto> search(String search) {
        // TODO Auto-generated method stub
        throw new UnsupportedOperationException("Unimplemented method 'search'");
    }




  
  
}
