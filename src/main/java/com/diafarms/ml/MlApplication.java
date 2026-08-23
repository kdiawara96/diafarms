package com.diafarms.ml;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.support.PropertySourcesPlaceholderConfigurer;
import org.springframework.core.io.FileSystemResource;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.commons.SuperAdminSeed;
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.RolesRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.security.RsakeysConfig;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;

@SpringBootApplication
@EnableConfigurationProperties(RsakeysConfig.class)
@EnableMethodSecurity()
@RequiredArgsConstructor
@PropertySource("classpath:application.properties")
public class MlApplication implements CommandLineRunner {
	
	private final RolesRepo rolesRepo;
    private final UtilisateursRepo utilisateursRepo;

    // Fichier de seed du SUPER_ADMIN de bootstrap — voir SuperAdminSeed et
    // loadSuperAdminSeed() : exécuté une seule fois (à la toute première mise en
    // route, quand aucun SUPER_ADMIN n'existe encore en base), jamais recommité avec
    // de vraies valeurs (voir super-admin-seed.example.json pour le modèle).
    private static final String SUPER_ADMIN_SEED_FILE = "super-admin-seed.json";

	public static void main(String[] args) {
		loadEnv();
		SpringApplication.run(MlApplication.class, args);
	}
	
	@Override
    public void run(String... args) {
        PasswordEncoder passwordEncoder = passwordEncoder();
        // =====================================================
        // 1️⃣ CREATION DU ROLE ADMIN S’IL N’EXISTE PAS
        // =====================================================
        String defaultRole = "ADMIN";
        String roleSUPER_ADMIN = "SUPER_ADMIN";
        String roleResponsable = "RESPONSABLE";
        String roleComptable = "COMPTABLE";
        String roleVente = "VENTE";
        String roleProduction = "PRODUCTION";

        String[] rolesToCheck = {defaultRole, roleSUPER_ADMIN, roleResponsable, roleComptable, roleVente, roleProduction};

        for (String roleName : rolesToCheck) {
            Roles role = rolesRepo.findByRole(roleName);

            
            if (role == null) {
                role = new Roles();
                role.setRole(roleName);
                role.setUniqueId(UUID.randomUUID().toString());
                role.setInitialisation(Initialisation.init());
                rolesRepo.save(role);

                System.out.println("✔ " + roleName + " créé !");
            } else {
                System.out.println("✔ " + roleName + " déjà existant.");
            }
        }

        // =====================================================
        // 2️⃣ CREATION DE L’UTILISATEUR SUPER_ADMIN PAR DEFAUT
        // =====================================================
        // SUPER_ADMIN est le SEUL rôle autorisé à exister sans ferme (compte
        // système de bootstrap, transversal à toutes les fermes, chargé de créer les
        // ADMIN de chaque ferme) — tous les autres comptes (ADMIN, PRODUCTEUR,
        // FINANCIER) sont créés avec une ferme obligatoire via UtilisateurImpl (soit
        // une nouvelle ferme à l'inscription, soit celle de l'admin qui les crée).
        // Voir les vérifications `currentUser.getFarm() != null` ajoutées dans les
        // services de lecture (Race, Batiment, Projets, Investissements, Logs,
        // Utilisateurs) : elles traitent ce compte comme "aucune donnée de ferme"
        // plutôt que de planter avec un NullPointerException.
        //
        // Vérifié par EXISTENCE D'UN SUPER_ADMIN (existsSuperAdmin()), jamais par
        // username exact : une base restaurée/migrée a déjà son propre SUPER_ADMIN
        // sous un autre nom que celui du JSON de seed, et recréer un second compte à
        // partir du fichier de seed à chaque démarrage serait un doublon silencieux.
        if (utilisateursRepo.existsSuperAdmin()) {
            System.out.println("✔ SUPER_ADMIN déjà existant, pas de création.");
        } else {
            SuperAdminSeed seed = loadSuperAdminSeed();
            if (seed == null) {
                System.out.println("⚠ Aucun SUPER_ADMIN en base et " + SUPER_ADMIN_SEED_FILE
                        + " introuvable/invalide — aucun compte créé, voir super-admin-seed.example.json.");
            } else {
                Utilisateurs admin = new Utilisateurs();
                admin.setUniqueId(UUID.randomUUID().toString());
                admin.setFullName(seed.getFullName());
                admin.setUsername(seed.getUsername());
                admin.setEmail(seed.getEmail());
                admin.setTelephone(seed.getTelephone());
                admin.setPassword(passwordEncoder.encode(seed.getPassword())); // 🔥 mot de passe encodé
                admin.setStatut(true);
                admin.setInitialisation(Initialisation.init());

                // role — SUPER_ADMIN, jamais ADMIN : voir commentaire ci-dessus.
                Set<Roles> roles = new HashSet<>();
                roles.add(rolesRepo.findByRole(roleSUPER_ADMIN));
                admin.setRoles(roles);

                utilisateursRepo.save(admin);

                System.out.println("✔ Utilisateur SUPER_ADMIN créé depuis " + SUPER_ADMIN_SEED_FILE + " !");
            }
        }

    }

    // Lu une seule fois, à la toute première mise en route de la plateforme (voir
    // run() : jamais réévalué une fois qu'un SUPER_ADMIN existe déjà en base, quel
    // que soit le contenu de ce fichier ensuite). null si absent/invalide plutôt
    // qu'une exception : un déploiement déjà initialisé n'a pas besoin de ce fichier.
    private SuperAdminSeed loadSuperAdminSeed() {
        File file = new File(SUPER_ADMIN_SEED_FILE);
        if (!file.exists()) return null;
        try {
            return new ObjectMapper().readValue(file, SuperAdminSeed.class);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

	@Bean
	public PasswordEncoder passwordEncoder(){
		return new BCryptPasswordEncoder(); 
	}
	
	// Chargement des variables d'environnement
	@Bean
    public static PropertySourcesPlaceholderConfigurer propertySourcesPlaceholderConfigurer() {
        PropertySourcesPlaceholderConfigurer configurer = new PropertySourcesPlaceholderConfigurer();
        configurer.setLocation(new FileSystemResource(".env"));
        return configurer;
    }
	
	private static void loadEnv() {
		try (FileInputStream fis = new FileInputStream(".env")) {
			Properties properties = new Properties();
			properties.load(fis);
			properties.forEach((key, value) -> {
				System.setProperty((String) key, (String) value);
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
