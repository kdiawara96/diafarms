package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Membre du personnel de la ferme, distinct d'un compte Utilisateurs (login) — un
// gardien ou tout employé n'ayant jamais besoin de se connecter à l'application peut
// avoir une fiche Personnel et une grille salariale (voir Salaire.employe) sans
// jamais posséder de compte. Un Personnel qui EST aussi un utilisateur système
// (ex: un vendeur salarié) peut être lié à son compte via utilisateurCompte, pour
// éviter d'encoder deux fois la même personne — ce lien reste optionnel et purement
// informatif, il ne change aucun droit d'accès.
@Entity
@Table(name = "personnel")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Personnel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "nom", nullable = false, length = 150)
    private String nom;

    @Column(name = "poste", length = 100)
    private String poste;

    @Column(name = "telephone", length = 30)
    private String telephone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "utilisateur_compte_id", unique = true)
    private Utilisateurs utilisateurCompte;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
