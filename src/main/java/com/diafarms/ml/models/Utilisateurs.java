package com.diafarms.ml.models;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
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

    @Column(name = "unique_id", nullable = false, unique = true)
    private String uniqueId;

    @Column(name = "full_name", nullable = false, length = 50)
    private String fullName;

    @Column(name = "username", nullable = false, length = 50, unique = true)
    private String username;

    @Column(name = "email", length = 50, unique = true)
    private String email;

    @Column(name = "telephone", length = 50, unique = true)
    private String telephone;

    @Column(name = "password")
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    @Column(name = "code_change_password", length = 8)
    private int codeChangePassword = 0;
    
    @Column(name = "statut")
    private Boolean statut = true;
    
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



    

}


