package com.diafarms.ml.models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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

// Ventilation d'une VenteOeufs (Finance, ferme entière) entre les projets qui ont
// contribué au lot vendu — mirroir d'InvestissementRepartition, mais côté recette
// au lieu de coût. La part de chaque projet est calculée au prorata de son stock
// d'œufs disponible au moment de la vente (voir VenteOeufsImpl.repartirEtVendre).
// Chaque ligne génère sa propre Transaction "entrée" rattachée directement à
// `projet` (jamais "commune") : c'est ce qui permet à computeChiffreAffairesReel(projet)
// de refléter le vrai chiffre d'affaires de CE projet.
@Entity
@Table(name = "ventes_oeufs_repartitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VenteOeufsRepartition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_oeufs_id", nullable = false)
    private VenteOeufs venteOeufs;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    @Column(name = "quantite_attribuee", nullable = false)
    private Integer quantiteAttribuee;

    @Column(name = "montant_attribue", nullable = false)
    private Double montantAttribue;
}
