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

    // Ville de la ferme, utilisée pour géolocaliser la météo (WeatherService) — une
    // ferme opère à un seul endroit, donc granularité farm plutôt que par projet.
    @Column(name = "ville", length = 100)
    private String ville;

    // Logo et tampon de la ferme — optionnels, insérés sur les factures et bulletins
    // de salaire générés en PDF (voir FactureServiceImpl/SalaireServiceImpl), laissés
    // vides si la ferme n'en a pas encore fourni. Stocke le nom d'objet MinIO (voir
    // FarmController, MinioService), jamais le fichier lui-même en base.
    @Column(name = "logo_nom_minio")
    private String logoNomMinio;

    @Column(name = "tampon_nom_minio")
    private String tamponNomMinio;

    @OneToMany(mappedBy = "farm")
    private List<Utilisateurs> utilisateurs;

}