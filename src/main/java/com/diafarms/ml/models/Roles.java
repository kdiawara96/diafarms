package com.diafarms.ml.models;

import java.util.ArrayList;
import java.util.List;
import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "roles")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter

public class Roles {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true)
    private String uniqueId;

    @Column(name = "role", nullable = false, length = 20, unique = true)
    private String role;

    @Embedded
    private Initialisation initialisation;


    //------------------------MAPPING------------------------
    @ManyToMany(fetch = FetchType.EAGER)
    @JsonIgnore
    @JoinTable(name = "roles_users", joinColumns = {
            @JoinColumn(name = "id_roles") }, inverseJoinColumns = {
            @JoinColumn(name = "id_utilisateurs") })
    List<Utilisateurs> utilisateurs = new ArrayList<>();
}
