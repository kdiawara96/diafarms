package com.diafarms.ml.ServiceImpl;

import lombok.RequiredArgsConstructor;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.diafarms.ml.DTO.UtilisateursDTO;
import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.commons.SecurityUtils;
import com.diafarms.ml.models.Farm;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.others.PaginatedResponse;
import com.diafarms.ml.repository.FarmsRepo;
import com.diafarms.ml.repository.RolesRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.request.create.UserCreate;
import com.diafarms.ml.request.update.UpdatePassResquest;
import com.diafarms.ml.request.update.UserUpdate;
import com.diafarms.ml.services.EmailService;
import com.diafarms.ml.services.LogsServices;
import com.diafarms.ml.services.UtilisateursServices;

import org.springframework.data.domain.Page;

import org.springframework.data.domain.Pageable;

import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;

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
    private final OtherService OtherService;
    private final EmailService emailService;

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

        // Mot de passe aléatoire fort (pas dérivé du nom : ne doit jamais être devinable)
        String generatedPassword = generateSecurePassword();
        user.setPassword(encoder.encode(generatedPassword));
        user.setMustChangePassword(true);

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

        boolean emailSent = emailService.sendWelcomeEmail(user.getEmail(), user.getFullName(), generatedUsername, generatedPassword);

        return UtilisateursDTO.fromEntity(user, generatedPassword, emailSent);
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

        SecureRandom random = new SecureRandom();
        String username;

        do {
            int number = random.nextInt(90) + 10; // génère un nombre entre 10 et 99
            username = baseUsername + number;
        } while (utilisateursRepo.existsByUsername(username));

        return username;
    }

    /**
     * Génère un username de style "prenom.nom" (utilisé pour les comptes
     * producteur/financier créés par un admin), en ajoutant un suffixe
     * numérique si ce nom d'utilisateur est déjà pris.
     */
    private String generateUsernameFromFullName(String fullName) {
        String base = fullName.trim().toLowerCase().replaceAll("\\s+", ".");
        if (!utilisateursRepo.existsByUsername(base)) {
            return base;
        }
        SecureRandom random = new SecureRandom();
        String username;
        do {
            username = base + (random.nextInt(90) + 10);
        } while (utilisateursRepo.existsByUsername(username));
        return username;
    }

    // Alphabets sans caractères ambigus (pas de 0/O, 1/l/I) pour rester lisible
    // si le mot de passe doit être retapé manuellement en secours.
    private static final String PWD_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PWD_LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String PWD_DIGITS = "23456789";
    private static final String PWD_ALL = PWD_UPPER + PWD_LOWER + PWD_DIGITS;
    private static final int PWD_LENGTH = 12;

    /**
     * Génère un mot de passe temporaire réellement aléatoire (SecureRandom),
     * jamais dérivé d'une donnée devinable comme le nom de l'utilisateur.
     * Garantit au moins une majuscule, une minuscule et un chiffre.
     */
    private String generateSecurePassword() {
        SecureRandom random = new SecureRandom();
        List<Character> chars = new ArrayList<>(PWD_LENGTH);
        chars.add(PWD_UPPER.charAt(random.nextInt(PWD_UPPER.length())));
        chars.add(PWD_LOWER.charAt(random.nextInt(PWD_LOWER.length())));
        chars.add(PWD_DIGITS.charAt(random.nextInt(PWD_DIGITS.length())));
        for (int i = chars.size(); i < PWD_LENGTH; i++) {
            chars.add(PWD_ALL.charAt(random.nextInt(PWD_ALL.length())));
        }
        Collections.shuffle(chars, random);

        StringBuilder password = new StringBuilder(PWD_LENGTH);
        chars.forEach(password::append);
        return password.toString();
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

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        Long farmId = null;
        if (currentUser != null && currentUser.getFarm() != null) {
            farmId = currentUser.getFarm().getId();
        }

        return utilisateursRepo.findAllByFarmIdAndNotRemoved(farmId).stream()
                .map(UtilisateursDTO::fromSelect)
                .collect(Collectors.toList());
    }

    @Override
    public List<UtilisateursDTO> selectProducteurs() {

        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        Long farmId = null;
        if (currentUser != null && currentUser.getFarm() != null) {
            farmId = currentUser.getFarm().getId();
        }

        return utilisateursRepo.findProducteursByFarmId(farmId).stream()
                .map(UtilisateursDTO::fromSelect)
                .collect(Collectors.toList());
    }

    @Override
    public List<UtilisateursDTO> selectFinanciers() {
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        Long farmId = null;
        if (currentUser != null && currentUser.getFarm() != null) {
            farmId = currentUser.getFarm().getId();
        }

        return utilisateursRepo.findFinanciersByFarmId(farmId).stream()
                .map(UtilisateursDTO::fromSelect)
                .collect(Collectors.toList());
    }

    // 1. Récupérer tous les utilisateurs
    @Override
    @Transactional(readOnly = true)
    public List<UtilisateursDTO> getAllUtilisateurs() {
        return utilisateursRepo.findAll().stream()
                .map(UtilisateursDTO::fromEntity)
                .collect(Collectors.toList());
    }

    // 2. Récupérer un utilisateur par son identifiant unique
    @Override
    @Transactional(readOnly = true)
    public UtilisateursDTO getUtilisateurByUniqueId(String uniqueId) {
        Utilisateurs utilisateur = utilisateursRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Utilisateur non trouvé avec l'ID: " + uniqueId));
        return UtilisateursDTO.fromEntity(utilisateur);
    }

    // 3. Créer un nouvel utilisateur
    @Override
    @Transactional
    public UtilisateursDTO createUtilisateurProdOrFinan(UserCreate dto) {
        // Récupération de la ferme de l'administrateur connecté
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Erreur lors de la récupération du contexte utilisateur.");
        }

        // Validation des doublons de téléphone dans la même ferme
        if (currentUser != null && currentUser.getFarm() != null) {
            if (utilisateursRepo.existsByTelephoneAndFarmId(dto.getTelephone().trim(), currentUser.getFarm().getId())) {
                throw new RuntimeException("Un utilisateur avec ce numéro de téléphone existe déjà dans votre ferme.");
            }
        }
             // Validate input data
        if (utilisateursRepo.existsByEmail(dto.getEmail())) {
            throw new IllegalArgumentException("L'email '" + dto.getEmail() + "' existe déjà.");
        }

        Utilisateurs u = new Utilisateurs();

        // Génération automatique des identifiants système sécurisés
        u.setUniqueId("USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        // Génération d'un username basé sur le fullName (ex: karim.diawara), avec
        // désambiguïsation si ce nom d'utilisateur existe déjà.
        String generatedUsername = generateUsernameFromFullName(dto.getFullName());
        u.setUsername(generatedUsername);
        u.setFullName(dto.getFullName());
        u.setTelephone(dto.getTelephone());
        u.setEmail(dto.getEmail());
        u.setCity(dto.getCity());
        u.setRegion(dto.getRegion());
        u.setStatut(true); // Actif par défaut

        // Association automatique à la ferme de l'admin connecté
        if (currentUser != null) {
            u.setFarm(currentUser.getFarm());
        }

        // Mot de passe temporaire aléatoire (pas un préfixe fixe + 4 caractères devinable)
        String plainPassword = generateSecurePassword();
        u.setPassword(encoder.encode(plainPassword));
        u.setMustChangePassword(true);

        // Traçabilité (Initialisation)
        Initialisation init = new Initialisation();
        init.setCreatedAt(LocalDateTime.now());
        init.setUpdatedAt(LocalDateTime.now());
        u.setInitialisation(init);

        // Mappage des rôles (Convertit le RoleDTO du front en entité Roles pour Hibernate)
        // 4. 🔄 Récupération et liaison des Rôles par leur libellé/nom
        if (dto.getRoles() != null && !dto.getRoles().isEmpty()) {
            Set<Roles> userRoles = dto.getRoles().stream()
                .map(roleName -> {
                    // Recherche du rôle actif et non archivé par son nom
                    Roles role = roleRepo.findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(roleName.trim());
                    if (role == null) {
                        throw new RuntimeException("Le rôle spécifié n'existe pas ou n'est plus actif : " + roleName);
                    }
                    return role;
                })
                .collect(Collectors.toSet());

            u.setRoles(userRoles);
        }

        Utilisateurs savedUser = utilisateursRepo.save(u);

        boolean emailSent = emailService.sendWelcomeEmail(savedUser.getEmail(), savedUser.getFullName(), generatedUsername, plainPassword);

        // On retourne le DTO avec le mot de passe en clair uniquement à la création pour affichage
        return UtilisateursDTO.fromEntity(savedUser, plainPassword, emailSent);
    }

    // 4. Modifier un utilisateur existant
    @Override
    @Transactional
    public UtilisateursDTO updateUtilisateur(String uniqueId, UserUpdate dto) {
        // 1. Recherche de l'utilisateur existant
        Utilisateurs u = utilisateursRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

        // 2. Validation optionnelle : Empêcher les doublons de téléphone avec un AUTRE utilisateur de la même ferme
        // (aucune vérification à faire pour un compte sans ferme, ex: SUPER_ADMIN)
        if (u.getFarm() != null && dto.getTelephone() != null && !dto.getTelephone().trim().equalsIgnoreCase(u.getTelephone())) {
            boolean phoneExists = utilisateursRepo.existsByTelephoneAndFarmId(
                    dto.getTelephone().trim(),
                    u.getFarm().getId()
            );
            if (phoneExists) {
                throw new RuntimeException("Un autre utilisateur possède déjà ce numéro de téléphone dans votre ferme.");
            }
        }

        // 3. Mise à jour des informations de base
        u.setFullName(dto.getFullName());
        u.setTelephone(dto.getTelephone());
        u.setEmail(dto.getEmail());
        u.setCity(dto.getCity());
        u.setRegion(dto.getRegion());
        
        // Traçabilité
        if (u.getInitialisation() != null) {
            u.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }

        // 4. 🔄 Mise à jour des Rôles par leur libellé (comme à la création)
        if (dto.getRoles() != null) {
            u.getRoles().clear(); // On nettoie les anciennes associations
            
            Set<Roles> updatedRoles = dto.getRoles().stream()
                .map(roleName -> {
                    Roles role = roleRepo.findByRoleAndInitialisationRemovedFalseAndInitialisationArchiveFalse(roleName.trim());
                    if (role == null) {
                        throw new RuntimeException("Le rôle spécifié n'existe pas ou est inactif : " + roleName);
                    }
                    return role;
                })
                .collect(Collectors.toSet());
            
            u.setRoles(updatedRoles);
        }

        Utilisateurs updatedUser = utilisateursRepo.save(u);
        return UtilisateursDTO.fromEntity(updatedUser);
    }

    // 5. Régénérer l'identifiant unique (Révocation de l'ancien QR code mobile)
    @Transactional
    public UtilisateursDTO regenerateQRCodeToken(String uniqueId) {
        Utilisateurs u = utilisateursRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));
        
        // En changeant le uniqueId, l'ancien QR code scanné par le mobile devient obsolète
        u.setUniqueId("USR-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        u.getInitialisation().setUpdatedAt(LocalDateTime.now());

        Utilisateurs updatedUser = utilisateursRepo.save(u);
        return UtilisateursDTO.fromEntity(updatedUser);
    }

    /**
     * Révoque un utilisateur en désactivant son compte et en invalidant son QR code.
     */
    @Override
    @Transactional
    public UtilisateursDTO revoquerUtilisateur(String uniqueId) {
        // 0. Un utilisateur ne peut pas révoquer/réactiver son propre accès
        try {
            if (uniqueId.equals(SecurityUtils.getCurrentUserUniqueId())) {
                throw new RuntimeException("Vous ne pouvez pas modifier votre propre statut d'accès.");
            }
        } catch (IllegalStateException e) {
            // Pas d'utilisateur authentifié résolu : on laisse passer, la sécurité globale
            // (JWT obligatoire) aura de toute façon déjà bloqué l'appel en amont.
        }

        // 1. Recherche de l'utilisateur
        Utilisateurs u = utilisateursRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

        // 2. Révocation des accès
        u.setStatut(!u.getStatut()); // Le compte passe en suspendu/inactif
        
        // On change le uniqueId pour couper immédiatement la session du QR Code actuel
        // u.setUniqueId("REVOC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());

        // 3. Traçabilité de la modification
        if (u.getInitialisation() != null) {
            u.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }

        Utilisateurs revokedUser = utilisateursRepo.save(u);
        return UtilisateursDTO.fromEntity(revokedUser);
    }

    @Override
    @Transactional
    public UtilisateursDTO changePassword(String uniqueId, UpdatePassResquest data) {
        Utilisateurs u = utilisateursRepo.findByUniqueId(uniqueId)
                .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

        if (data.getOldPassword() == null || !encoder.matches(data.getOldPassword(), u.getPassword())) {
            throw new RuntimeException("Le mot de passe actuel est incorrect.");
        }
        if (data.getPassword() == null || data.getPassword().length() < 8) {
            throw new RuntimeException("Le nouveau mot de passe doit contenir au moins 8 caractères.");
        }

        u.setPassword(encoder.encode(data.getPassword()));
        u.setMustChangePassword(false);
        if (u.getInitialisation() != null) {
            u.getInitialisation().setUpdatedAt(LocalDateTime.now());
        }
        Utilisateurs saved = utilisateursRepo.save(u);

        logsServices.addLogs(u.getId(), u.getId(), "Utilisateurs", "Changement de mot de passe par l'utilisateur lui-même");

        return UtilisateursDTO.fromEntity(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public PaginatedResponse<UtilisateursDTO> getAllUtilisateurs(String searchTerm, int page, int size) {
        // 1. Récupération du contexte de la ferme de l'admin connecté
        Utilisateurs currentUser = null;
        try {
            currentUser = OtherService.getCurrentUser();
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("Erreur de récupération du contexte utilisateur.");
        }

        if (currentUser == null || currentUser.getFarm() == null) {
            throw new RuntimeException("Aucune exploitation associée à votre compte.");
        }

        // 2. Préparation de la pagination Spring Data (Zéro-indexed)
        Pageable pageable = PageRequest.of(page, size, Sort.by("id").descending());

        // 3. Exécution de la recherche paginée en BDD
        Page<Utilisateurs> usersPage = utilisateursRepo.searchUsersByFarm(
                currentUser.getFarm().getId(), 
                searchTerm != null ? searchTerm.trim() : null, 
                pageable
        );

        // 4. Conversion des entités en DTOs
        List<UtilisateursDTO> dtos = usersPage.getContent().stream()
                .map(UtilisateursDTO::fromEntity)
                .collect(Collectors.toList());

        // 5. Encapsulation dans ton modèle PaginatedResponse
        return new PaginatedResponse<>(
                dtos,
                usersPage.getNumber(),      // Page courante
                usersPage.getTotalPages(),  // Nombre total de pages
                usersPage.getTotalElements(), // Nombre total d'éléments
                usersPage.getSize()         // Taille de la page
        );
    }

}
