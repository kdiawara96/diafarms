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

    private LocalDate dateFin; // Null si toujours en cours d'utilisation sur ce projet

    @Column(nullable = false)
    private Integer moisUtilises;

    @Column(nullable = false)
    private Double montantAlloue; // La part financière d'amortissement supportée par ce projet
}