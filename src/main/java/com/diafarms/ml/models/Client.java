package com.diafarms.ml.models;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Client d'une ferme (acheteur d'œufs/réforme) — optionnel sur une vente (voir
// VenteOeufs.client/VenteReforme.client) : on peut vendre sans client identifié
// ("vente directe"), auquel cas l'écart théorique/rapporté reste attribué au
// vendeur (SoldeVendeur). Quand un client est renseigné, ce même écart devient une
// dette du client plutôt que du vendeur — voir SoldeClient.
@Entity
@Table(name = "clients")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Client {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "nom", nullable = false, length = 150)
    private String nom;

    // Obligatoire et unique par ferme (voir ClientRepo.existsByTelephoneAndFarmId,
    // ClientServiceImpl.create/update) — ddl-auto=update n'altère jamais la nullabilité
    // d'une colonne existante, l'ALTER TABLE (SET NOT NULL + contrainte unique
    // (farm_id, telephone)) a été appliqué manuellement en base.
    @Column(name = "telephone", length = 30, nullable = false)
    private String telephone;

    @Column(name = "adresse", length = 300)
    private String adresse;

    @Column(name = "email", length = 150)
    private String email;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @Embedded
    private Initialisation initialisation;
}
