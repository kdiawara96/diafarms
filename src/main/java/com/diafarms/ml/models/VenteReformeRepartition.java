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

// Ventilation d'une VenteReforme (Finance, ferme entière) entre les projets ayant
// contribué au lot vendu — voir VenteOeufsRepartition pour le détail du mécanisme.
@Entity
@Table(name = "ventes_reforme_repartitions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class VenteReformeRepartition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vente_reforme_id", nullable = false)
    private VenteReforme venteReforme;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "projet_id", nullable = false)
    private Projets projet;

    @Column(name = "nombre_sujets_attribue", nullable = false)
    private Integer nombreSujetsAttribue;

    @Column(name = "montant_attribue", nullable = false)
    private Double montantAttribue;
}
