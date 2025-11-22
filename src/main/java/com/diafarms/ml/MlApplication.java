package com.diafarms.ml;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;
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
import com.diafarms.ml.repository.RolesRepo;
import com.diafarms.ml.security.RsakeysConfig;

import lombok.RequiredArgsConstructor;

@SpringBootApplication
@EnableConfigurationProperties(RsakeysConfig.class)
@EnableMethodSecurity()
@RequiredArgsConstructor
@PropertySource("classpath:application.properties")
public class MlApplication implements CommandLineRunner {
	
	private final RolesRepo rolesRepo;


	public static void main(String[] args) {
		loadEnv();
		SpringApplication.run(MlApplication.class, args);
	}
	@Override
    public void run(String... args) {

        // Le rôle que tu veux créer
        String defaultRole = "ROLE_ADMIN";

        // Vérification
        Roles existing = rolesRepo.findByRole(defaultRole);

        if (existing == null) {
            Roles role = new Roles();
            role.setRole(defaultRole);
            role.setUniqueId(UUID.randomUUID().toString());
			role.setInitialisation(Initialisation.init());

            rolesRepo.save(role);

            System.out.println("✔ ROLE_ADMIN créé !");
        } else {
            System.out.println("✔ ROLE_ADMIN déjà existant, pas de création.");
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
