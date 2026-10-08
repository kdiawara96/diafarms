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

    // Pays (code ISO 3166 alpha-2, "AUTRE" hors liste) et devise (ISO 4217) de la ferme,
    // choisis par le propriétaire dans Paramètres > Pays et devise. Nullable (ajout
    // ddl-auto sur une table existante) : null = ML / XOF, la configuration d'origine.
    // La devise n'est qu'une unité d'affichage et d'arrondi (voir commons.Devise) :
    // aucun montant n'est jamais converti. Distinct de `pays` ci-dessus, texte libre
    // de l'adresse imprimée sur les factures.
    @Column(name = "pays_code", length = 10)
    private String paysCode;

    @Column(name = "devise", length = 3)
    private String devise;

    // Ferme exclue des statistiques de la console SUPER_ADMIN (ex. ferme de démonstration) :
    // toujours listée dans « Fermes », mais jamais comptée dans les chiffres, revenus,
    // conversion ni pertes. Nullable (ajout ddl-auto) : null = comptée. Les rappels
    // d'abonnement, eux, ne changent pas.
    @Column(name = "exclure_statistiques")
    private Boolean exclureStatistiques;

    // Croissance (lot 2), colonnes nullables (ajout ddl-auto sur une table existante) :
    //  - guideDemarrageMasqueLe : l'ADMIN a fermé la carte « Bien démarrer » du tableau de
    //    bord (null = carte affichée tant que les étapes ne sont pas toutes faites) ;
    //  - resumeHebdo : résumé de la semaine par e-mail le lundi (null ou true = oui,
    //    false = le propriétaire de la ferme l'a coupé dans Paramètres) ;
    //  - codeParrainage : code à partager pour parrainer une autre ferme, créé à la
    //    première demande (voir ParrainageService) ;
    //  - mobileConnecteLe : première connexion d'un compte de la ferme sur l'application
    //    mobile (scan du QR ou connexion mobile), étape « Installer l'application mobile ».
    @Column(name = "guide_demarrage_masque_le")
    private java.time.LocalDateTime guideDemarrageMasqueLe;

    @Column(name = "resume_hebdo")
    private Boolean resumeHebdo;

    @Column(name = "code_parrainage", length = 20, unique = true)
    private String codeParrainage;

    @Column(name = "mobile_connecte_le")
    private java.time.LocalDateTime mobileConnecteLe;

    @OneToMany(mappedBy = "farm")
    private List<Utilisateurs> utilisateurs;

}