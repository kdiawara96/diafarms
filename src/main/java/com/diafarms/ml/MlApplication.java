package com.diafarms.ml;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
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
import com.diafarms.ml.models.Roles;
import com.diafarms.ml.models.Utilisateurs;
import com.diafarms.ml.repository.RolesRepo;
import com.diafarms.ml.repository.UtilisateursRepo;
import com.diafarms.ml.security.RsakeysConfig;

import lombok.RequiredArgsConstructor;

@SpringBootApplication
@EnableConfigurationProperties(RsakeysConfig.class)
@EnableMethodSecurity()
@RequiredArgsConstructor
@PropertySource("classpath:application.properties")
public class MlApplication implements CommandLineRunner {
	
	private final RolesRepo rolesRepo;
    private final UtilisateursRepo utilisateursRepo;

    // Identifiants de l'admin par défaut créé au premier démarrage : lus depuis .env
    // (jamais commité) plutôt que codés en dur, pour ne pas exposer de vrais
    // identifiants dans le code source versionné. Les valeurs par défaut ci-dessous
    // ne s'appliquent que si les variables ADMIN_* sont absentes de l'environnement.
    @Value("${ADMIN_USERNAME:admin}")
    private String adminUsername;
    @Value("${ADMIN_PASSWORD:azerty123}")
    private String adminPassword;
    @Value("${ADMIN_EMAIL:admin@diafarms.local}")
    private String adminEmail;
    @Value("${ADMIN_TELEPHONE:}")
    private String adminTelephone;
    @Value("${ADMIN_FULLNAME:Super Administrateur}")
    private String adminFullName;

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
        String roleProducteur = "PRODUCTEUR";
        String roleFinancier = "FINANCIER";
        String roleSUPER_ADMIN = "SUPER_ADMIN";

        String[] rolesToCheck = {defaultRole, roleProducteur, roleFinancier, roleSUPER_ADMIN};

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
        // système de bootstrap, transversal à toutes les fermes) — tous les
        // autres comptes (ADMIN, PRODUCTEUR, FINANCIER) sont créés avec une
        // ferme obligatoire via UtilisateurImpl (soit une nouvelle ferme à
        // l'inscription, soit celle de l'admin qui les crée). Voir les
        // vérifications `currentUser.getFarm() != null` ajoutées dans les
        // services de lecture (Race, Batiment, Projets, Investissements,
        // Logs, Utilisateurs) : elles traitent ce compte comme "aucune
        // donnée de ferme" plutôt que de planter avec un NullPointerException.
        if (!utilisateursRepo.existsByUsername(adminUsername)) {

            Utilisateurs admin = new Utilisateurs();
            admin.setUniqueId(UUID.randomUUID().toString());
            admin.setFullName(adminFullName);
            admin.setUsername(adminUsername);
            admin.setEmail(adminEmail);
            admin.setTelephone(adminTelephone);
            admin.setPassword(passwordEncoder.encode(adminPassword)); // 🔥 mot de passe encodé
            admin.setStatut(true);
            admin.setInitialisation(Initialisation.init());

            // role — SUPER_ADMIN, jamais ADMIN : voir commentaire ci-dessus.
            Set<Roles> roles = new HashSet<>();
            roles.add(rolesRepo.findByRole(roleSUPER_ADMIN));
            admin.setRoles(roles);

            utilisateursRepo.save(admin);

            System.out.println("✔ Utilisateur SUPER_ADMIN créé !");
        } else {
            System.out.println("✔ Admin déjà existant, pas de création.");
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
