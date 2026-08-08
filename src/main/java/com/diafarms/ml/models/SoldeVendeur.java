package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Solde cumulé, reporté d'une vente à l'autre, entre le montant théorique et le
// montant réellement rapporté par un vendeur (voir VenteOeufs/VenteReforme
// .montant/montantRapporte) — positif = le vendeur doit de l'argent à la ferme
// (dette), négatif = trop rapporté (crédit, ex: remboursement d'une dette
// précédente). Un solde par (vendeur, ferme), mis à jour à chaque vente créée/
// modifiée/supprimée par ce vendeur — voir SoldeVendeurServiceImpl.
@Entity
@Table(name = "soldes_vendeur")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class SoldeVendeur {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vendeur_id", nullable = false, unique = true)
    private Utilisateurs vendeur;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Column(name = "solde", nullable = false)
    private Double solde = 0.0;

    @Embedded
    private Initialisation initialisation;
}
