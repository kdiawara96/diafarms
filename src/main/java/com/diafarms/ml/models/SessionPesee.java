package com.diafarms.ml.models;

import java.time.LocalDateTime;

import com.diafarms.ml.commons.Initialisation;
import com.diafarms.ml.enums.StatutSessionPesee;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Session de pesée d'un projet : une série de pesées (n sujets pesés ensemble) saisies
// sur le téléphone, hors ligne, puis synchronisées. L'uniqueId est un UUID généré par le
// téléphone (renvoi idempotent). Les totaux sont TOUJOURS recalculés côté serveur à
// partir des pesées non annulées, jamais repris du client.
@Entity
@Table(name = "sessions_pesee")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SessionPesee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cree_par_id")
    private Utilisateurs creePar;

    // Nombre de sujets proposé par défaut pour chaque pesée (≥ 1).
    @Column(name = "nombre_par_defaut", nullable = false)
    private Integer nombreParDefaut;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StatutSessionPesee statut;

    @Column(name = "date_debut", nullable = false)
    private LocalDateTime dateDebut;

    // Null tant que la session est EN_COURS.
    @Column(name = "date_fin")
    private LocalDateTime dateFin;

    // Date/heure de la dernière pesée non annulée.
    @Column(name = "derniere_date_pesee")
    private LocalDateTime derniereDatePesee;

    // Totaux recalculés à chaque synchronisation (pesées non annulées).
    @Column(name = "nombre_total_sujets")
    private Integer nombreTotalSujets;

    @Column(name = "poids_total_kg")
    private Double poidsTotalKg;

    @Column(name = "poids_moyen_kg")
    private Double poidsMoyenKg;

    @Embedded
    private Initialisation initialisation;
}
