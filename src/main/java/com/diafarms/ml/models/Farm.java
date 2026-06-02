package com.diafarms.ml.models;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

import jakarta.persistence.*;
@Entity
@Table(name = "farms")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Farm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true , length = 50)
    private String uniqueId;

    @OneToMany(mappedBy = "farm")
    private List<Utilisateurs> utilisateurs;

}