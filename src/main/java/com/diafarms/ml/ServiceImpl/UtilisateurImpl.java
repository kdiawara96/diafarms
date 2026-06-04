package com.diafarms.ml.ServiceImpl;

import lombok.RequiredArgsConstructor;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.UtilisateursDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.repository.RolesRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.create.UserCreate;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.UtilisateursServices;

import jakarta.transaction.Transactional;

/**
 * Implementation of user service for creating users.
 */
@Service
@RequiredArgsConstructor
public class UtilisateurImpl implements UtilisateursServices {

    private final UtilisateursRepo utilisateursRepo;
    private final PasswordEncoder encoder;
    private final LogsServices logsServices;
    private final RolesRepo roleRepo;
    private final FarmsRepo farmsRepo;

    @Override
    @Transactional
    public UtilisateursDTO save(UserCreate data) {

        // Validate input data
        if (utilisateursRepo.existsByEmail(data.getEmail())) {
            throw new IllegalArgumentException("L'email '" + data.getEmail() + "' existe déjà.");
        }
        if (utilisateursRepo.existsByTelephone(data.getTelephone())) {
            throw new IllegalArgumentException("Le numéro de téléphone '" + data.getTelephone() + "' existe déjà.");
        }

        // Create new user
        Utilisateurs user = new Utilisateurs();
        user.setFullName(data.getFullName());
        user.setRegion(data.getRegion());
        user.setCity(data.getCity());
        user.setFarmName(data.getFarmName());
        user.setEmail(data.getEmail());
        user.setTelephone(data.getTelephone());
        user.setUniqueId(UUID.randomUUID().toString());
        user.setInitialisation(Initialisation.init());

        // ==========================================
        // ETAPE NOUVELLE : Création et liaison de la ferme
        // ==========================================
        Farm newFarm = new Farm();
        // Génère un identifiant unique de 50 caractères max (ex: UUID tronqué ou complet selon vos besoins)
        String farmUuid = UUID.randomUUID().toString(); 
        newFarm.setUniqueId(farmUuid);
        
        // On sauvegarde d'abord la ferme pour générer son ID
        Farm savedFarm = farmsRepo.save(newFarm);
        
        // On lie la ferme à l'utilisateur (on suppose que votre entité Utilisateurs possède la méthode setFarm)
        user.setFarm(savedFarm); 
        // ==========================================

        // Generate unique username
        String generatedUsername = generateUsername(data.getFullName());
        user.setUsername(generatedUsername);

        // Generate random 8-digit password
        String generatedPassword = generatePassword(data.getFullName());
        user.setPassword(encoder.encode(generatedPassword));

        // Assign roles
        Set<Roles> rolesToAdd = new HashSet<>();
        if (data.getRoles() != null) {
            for (String role : data.getRoles()) {
                Roles role_geting = roleRepo.findByRoleAndInitialisationRemovedFalse(role)
                        .orElseThrow(() -> new IllegalArgumentException("Le rôle '" + role + "' n'existe pas."));
                rolesToAdd.add(role_geting);
            }
        }
        user.setRoles(rolesToAdd);

        // Save user (maintenant qu'il a son farm_id positionné)
        utilisateursRepo.save(user);

        // Log creation
        // Utilisateurs currentUser = getCurrentUser();
        if (user != null) {
            logsServices.addLogs(user.getId(), user.getId(), "User", "Création de l'utilisateur et de sa ferme");
        }

        return UtilisateursDTO.fromEntity(user, generatedPassword);
    }

    /**
     * Generates a unique username based on the full name.
     *
     * @param fullName the user's full name
     * @return a unique username
     */
    private String generateUsername(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            throw new IllegalArgumentException("Le nom complet ne peut pas être vide.");
        }

        String baseUsername = fullName.toLowerCase()
                .replaceAll("[^a-z]", "")
                .substring(0, Math.min(10, fullName.length()));

        Random random = new Random();
        String username;

        do {
            int number = random.nextInt(90) + 10; // génère un nombre entre 10 et 99
            username = baseUsername + number;
        } while (utilisateursRepo.existsByUsername(username));

        return username;
    }

    /**
     * Generates an 8-digit numeric password.
     *
     * @return an 8-digit password string
     */
    private String generatePassword(String fullName) {
        if (fullName == null || fullName.trim().isEmpty()) {
            return generateFallbackPassword();
        }
        
        String[] parts = fullName.trim().toLowerCase().split("\\s+");
        StringBuilder result = new StringBuilder();
        
        // Remplir avec les initiales ou syllabes
        for (String part : parts) {
            if (result.length() >= 6) break; // Réserver 2 places pour les chiffres
            
            if (part.length() >= 2) {
                // 2 premières lettres : première majuscule, deuxième minuscule
                result.append(Character.toUpperCase(part.charAt(0)));
                if (result.length() < 6) {
                    result.append(Character.toLowerCase(part.charAt(1)));
                }
            } else if (part.length() == 1) {
                result.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        
        // Compléter avec des lettres du premier mot si moins de 6 caractères
        String firstWord = parts[0];
        while (result.length() < 6 && result.length() < firstWord.length()) {
            result.append(Character.toLowerCase(firstWord.charAt(result.length())));
        }
        
        // Si toujours pas assez, compléter avec des lettres fixes
        String filler = "farm";
        int fillerIndex = 0;
        while (result.length() < 6) {
            result.append(filler.charAt(fillerIndex % filler.length()));
            fillerIndex++;
        }
        
        // 2 chiffres dérivés du nom (somme ASCII des initiales % 100)
        int seed = 0;
        for (String part : parts) {
            seed += part.charAt(0);
        }
        int number = (seed * parts.length) % 100;
        String numberPart = String.format("%02d", number);
        
        // Assembler : exactement 6 lettres + 2 chiffres = 8 caractères
        String password = result.substring(0, 6) + numberPart;
        return password;
    }

    private String generateFallbackPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";
        StringBuilder password = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            password.append(chars.charAt((int) (Math.random() * chars.length())));
        }
        // 2 chiffres
        password.append((int) (Math.random() * 10));
        password.append((int) (Math.random() * 10));
        return password.toString();
    }

    /**
     * Gets the currently authenticated user.
     *
     * @return the authenticated user
     */
    private Utilisateurs getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt) {
            String username = jwt.getSubject();
            return utilisateursRepo.findByUsername(username).orElseThrow(() -> new RuntimeException("Utilisateur non trouvé"));
        }
        return null;
    }

    @Override
    public Utilisateurs readByUsernameOrEmail(String usernameOrEmail) {
        return utilisateursRepo.findByUsernameOrEmailOrTelephone(usernameOrEmail,
                                                                usernameOrEmail,
                                                                usernameOrEmail)
                .orElseThrow(() -> new RuntimeException(
                        "Aucun utilisateur trouvé avec le nom d'utilisateur ou l'email : '" + usernameOrEmail + "'"
                ));
    }

    @Override
    public List<UtilisateursDTO> select() {
        return utilisateursRepo.findAll().stream()
                .map(UtilisateursDTO::fromSelect)
                .collect(Collectors.toList());
    }
}
