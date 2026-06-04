package com.diafarms.ml;

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
        // 2️⃣ CREATION DE L’UTILISATEUR ADMIN PAR DEFAUT
        // =====================================================
        String adminUsername = "admin";

        if (!utilisateursRepo.existsByUsername(adminUsername)) {

            Utilisateurs admin = new Utilisateurs();
            admin.setUniqueId(UUID.randomUUID().toString());
            admin.setFullName("Super Administrateur");
            admin.setUsername("admin");
            admin.setEmail("karimdiawara96@gmail.com");
            admin.setTelephone("83918699");
            admin.setPassword(passwordEncoder.encode("azerty123")); // 🔥 mot de passe encodé
            admin.setStatut(true);
            admin.setInitialisation(Initialisation.init());

            // role
            Set<Roles> roles = new HashSet<>();
            roles.add(rolesRepo.findByRole(defaultRole)); // ADMIN
            admin.setRoles(roles);

            utilisateursRepo.save(admin);

            System.out.println("✔ Utilisateur ADMIN créé !");
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
