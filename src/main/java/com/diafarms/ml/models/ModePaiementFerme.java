package com.diafarms.ml.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Modes de paiement configurés par une ferme (Paramètres > Modes de paiement). Aucune
// ligne pour une ferme = liste proposée pour son pays, tous actifs (voir
// commons.CataloguePays, ModesPaiementService) : rien à migrer pour les fermes
// existantes. Le propriétaire coche/décoche les modes et en ajoute (code PERSO_...).
//
// Stockage d'un paiement : l'enum ModePaiement historique (colonne `mode`, contrainte
// CHECK Postgres) ne change pas. Un mode qui n'est pas une valeur de l'enum (Free Money,
// MTN, mode ajouté par la ferme) est enregistré en AUTRE + son libellé dans la colonne
// nullable `mode_libelle` des paiements et remboursements clients : aucune ALTER à faire.
@Entity
@Table(name = "modes_paiement_ferme",
        uniqueConstraints = @UniqueConstraint(columnNames = {"farm_id", "code"}))
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class ModePaiementFerme {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 60)
    private String libelle;

    @Column(nullable = false)
    private Boolean actif = true;

    @Column(nullable = false)
    private Integer ordre = 0;
}
