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

    // Ville de la ferme, utilisée pour géolocaliser la météo (WeatherService) et
    // affichée avec le reste des coordonnées ci-dessous sur les factures/bulletins.
    @Column(name = "ville", length = 100)
    private String ville;

    // Coordonnées de la ferme — optionnelles, affichées dans l'en-tête des factures
    // et bulletins de salaire générés en PDF (voir PdfStyle/FactureServiceImpl/
    // SalaireServiceImpl), configurables depuis Paramètres > Identité de la ferme.
    @Column(name = "nom", length = 150)
    private String nom;

    @Column(name = "quartier", length = 150)
    private String quartier;

    @Column(name = "pays", length = 100)
    private String pays;

    @Column(name = "telephone1", length = 50)
    private String telephone1;

    @Column(name = "telephone2", length = 50)
    private String telephone2;

    @Column(name = "email", length = 100)
    private String email;

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