package com.diafarms.ml.models;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;

@Entity
@Table(name = "investissement_repartitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvestissementRepartition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Liaison vers l'investissement
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "investissement_id", nullable = false)
    private Investissement investissement;

    // 🟢 LA CORRECTION : Liaison forte vers ton entité Projets
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet; 

    @Column(nullable = false)
    private LocalDate dateDebut;

    private LocalDate dateFin; // Figé à la clôture

    @Column(nullable = false)
    private Integer moisUtilises; // Figé à la clôture

    @Column(nullable = false)
    private Double montantAlloue; // La part financière d'amortissement supportée par ce projet


    // =========================================================================
    // METHODE DE PRE-ARCHIVAGE (Calculée avant d'enregistrer en dur à la clôture)
    // =========================================================================
    public void figerLaVentilation(LocalDate finDuProjet) {
        this.dateFin = finDuProjet;
        
        if (this.dateDebut != null && this.dateFin != null) {
            long jours = java.time.temporal.ChronoUnit.DAYS.between(this.dateDebut, this.dateFin);
            double moisCalcul = jours / 30.4375;
            
            this.moisUtilises = (int) Math.round(moisCalcul);
            
            if (this.investissement != null) {
                double totalImpute = moisCalcul * this.investissement.getAmortissementMensuel();
                // On plafonne au montant max de l'investissement pour rester cohérent
                this.montantAlloue = Math.min(this.investissement.getMontant(), Math.round(totalImpute * 100.0) / 100.0);
            }
        }
    }
}