package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Solde cumulé, reporté d'une vente à l'autre, entre le montant théorique et le
// montant réellement rapporté d'une vente faite À CE CLIENT (voir VenteOeufs/
// VenteReforme.client) — positif = le client doit de l'argent à la ferme (vente à
// crédit pas encore intégralement payée), négatif = trop payé (avance sur une
// prochaine vente). Même principe que SoldeVendeur, mais utilisé UNIQUEMENT quand
// une vente a un client identifié — une vente sans client continue d'imputer
// l'écart au solde du vendeur (voir VenteOeufsImpl/VenteReformeImpl).
@Entity
@Table(name = "soldes_client")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class SoldeClient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "client_id", nullable = false, unique = true)
    private Client client;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Column(name = "solde", nullable = false)
    private Double solde = 0.0;

    @Embedded
    private Initialisation initialisation;
}
