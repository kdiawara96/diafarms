package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Fiche salariale d'un employé (Utilisateurs) — un seul salaire de base par employé
// (voir SalaireServiceImpl.definir, upsert). L'historique des paiements réels vit
// dans PaiementSalaire, un par période payée — voir "Payer le salaire".
@Entity
@Table(name = "salaires")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Salaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employe_id", nullable = false, unique = true)
    private Utilisateurs employe;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Column(name = "montant_mensuel", nullable = false)
    private Double montantMensuel;

    @Embedded
    private Initialisation initialisation;
}
