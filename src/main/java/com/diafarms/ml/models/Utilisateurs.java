package com.diafarms.ml.models;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "utilisateurs")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@Inheritance(strategy = InheritanceType.JOINED)
public class Utilisateurs {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "full_name", nullable = false, length = 50)
    private String fullName;

    @Column(name = "region", length = 50)
    private String region;

    @Column(name = "city", length = 50)
    private String city;

    @Column(name = "farm_name", length = 50)
    private String farmName;

    @Column(name = "photo", length = 50)
    private String photo;

    @Column(name = "username", nullable = false, length = 50, unique = true)
    private String username;

    @Column(name = "email", length = 50, unique = true)
    private String email;

    @Column(name = "telephone", length = 50, unique = true)
    private String telephone;

    @Column(name = "info_qrcode_encrypte", columnDefinition = "TEXT")
    private String infoQrcodeEncrypte; // Le token ou l'URL cryptée

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @Column(name = "qr_generated_at", length = 50)
    private LocalDateTime qrGeneratedAt; // Date de début (création)
    
    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @Column(name = "qr_expires_at", length = 50)
    private LocalDateTime qrExpiresAt;   // Date de fin (expiration)

    @Column(name = "password")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(name = "code_change_password", length = 8)
    private int codeChangePassword = 0;
    
    @Column(name = "statut")
    private Boolean statut = true;

    // Force le changement de mot de passe à la prochaine connexion (comptes créés
    // avec un mot de passe généré automatiquement). Boolean (pas boolean) : les
    // lignes existantes restent NULL après l'ALTER TABLE, traité comme "false".
    @Column(name = "must_change_password")
    private Boolean mustChangePassword = false;

    // Révocation immédiate des sessions web (déconnexion "réelle") : embarqué comme
    // claim dans chaque access/refresh token émis par mot de passe (voir AuthImpl.jwt),
    // incrémenté à la déconnexion (voir authControllers.logout). Un token web déjà
    // émis dont le tokenVersion ne correspond plus à celui en base devient invalide
    // immédiatement, plutôt que de rester valable jusqu'à son expiration naturelle
    // (7 jours) même après clic sur "Déconnexion". Ne concerne QUE les tokens web
    // (mot de passe) : les tokens QR mobile (type=QR_CODE, voir QRCodeService) n'ont
    // pas ce claim et ne sont jamais affectés — une déconnexion web ne doit pas
    // couper l'accès mobile déjà distribué. Boolean (pas Integer) évité ici
    // volontairement : Integer, jamais NULL en pratique après ddl-auto=update grâce
    // au défaut Java, mais traité comme 0 si NULL par sécurité côté code (lignes
    // existantes restées NULL après l'ALTER TABLE).
    @Column(name = "token_version")
    private Integer tokenVersion = 0;

    // Mot de passe oublié : code à 6 chiffres envoyé par email, à usage unique
    // et à courte durée de vie (voir PasswordResetServiceImpl). String (pas
    // int) pour ne pas perdre les zéros en tête du code.
    @Column(name = "reset_password_code", length = 6)
    private String resetPasswordCode;

    @Column(name = "reset_password_code_expiry")
    private LocalDateTime resetPasswordCodeExpiry;

    @JsonFormat(pattern = "dd-MM-yy HH:mm", shape = JsonFormat.Shape.STRING)
    @Column(name = "last_login", length = 50)
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private LocalDateTime lastLogin;
    
    @Embedded
    private Initialisation initialisation;

    //============================================================================================================================
    // ============================================ RELATION =====================================================================
    //============================================================================================================================

   
    //MAPPAGE ENTRE LA CLASSE UTILISATEURS ET LA CLASSE ROLES
    @ManyToMany(fetch = FetchType.EAGER, cascade = CascadeType.REMOVE)
    @JoinTable(name = "roles_users", joinColumns = {
            @JoinColumn(name = "id_utilisateurs") },
            inverseJoinColumns = {
            @JoinColumn(name = "id_roles") })
            
    private Set<Roles> roles;

    @OneToMany(mappedBy = "utilisateur", fetch = FetchType.LAZY)
    private List<Investissement> investissements = new ArrayList<>();


    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id")
    private Farm farm;

    @OneToMany(mappedBy = "responsableProduction", fetch = FetchType.LAZY)
    private List<Projets> projetsEnProduction = new ArrayList<>();

    @OneToMany(mappedBy = "responsableFinance", fetch = FetchType.LAZY)
    private List<Projets> projetsEnFinance = new ArrayList<>();


}


