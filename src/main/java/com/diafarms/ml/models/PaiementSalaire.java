package com.diafarms.ml.models;

import java.time.LocalDate;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Un paiement de salaire pour UNE période (format "AAAA-MM", ex "2026-08") — au plus un
// par (salaire, periode), voir SalaireServiceImpl.payer. Génère systématiquement une
// vraie Transaction (SORTIE, catégorie "Salaires", SourceTransaction.SALAIRE pointant
// vers uniqueId ici) plutôt que de laisser l'utilisateur ressaisir une transaction
// manuelle non structurée.
@Entity
@Table(name = "paiements_salaire")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class PaiementSalaire {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "salaire_id", nullable = false)
    private Salaire salaire;

    @Column(name = "periode", nullable = false, length = 7)
    private String periode;

    @Column(name = "montant_paye", nullable = false)
    private Double montantPaye;

    // Nombre de jours/heures travaillés pour cette période — renseigné uniquement si
    // le Salaire était en mode JOURNALIER/HORAIRE au moment du paiement (null pour
    // MENSUEL), gardé pour trace même si la grille change ensuite.
    @Column(name = "quantite")
    private Double quantite;

    @Column(name = "date_paiement", nullable = false)
    private LocalDate datePaiement;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    @Embedded
    private Initialisation initialisation;
}
