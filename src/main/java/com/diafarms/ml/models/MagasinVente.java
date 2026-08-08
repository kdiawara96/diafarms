package com.diafarms.ml.models;

import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.*;

import com.diafarms.ml.commons.Initialisation;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Point de vente avec son propre stock (œufs/réforme), alimenté par des transferts
// explicites depuis le stock disponible d'un projet (voir MagasinTransfert) — remplace
// la répartition automatique proportionnelle qui existait à la vente (voir
// VenteOeufsImpl/VenteReformeImpl, désormais scopés par magasin plutôt que farm-wide).
// Un utilisateur VENTE ne peut vendre que depuis un magasin auquel il est lié.
@Entity
@Table(name = "magasins_vente")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class MagasinVente {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "nom", nullable = false, length = 100)
    private String nom;

    @Column(name = "description", length = 500)
    private String description;

    // Seuils d'alerte stock bas (nullable = alerte désactivée pour ce type dans ce
    // magasin) — configurés par l'admin/responsable à la création/modification du
    // magasin. Voir NotificationServiceImpl.addMagasinStockAlerts : notifie les
    // RESPONSABLE des projets qui contribuent actuellement au stock de CE magasin dès
    // que le stock passe sous le seuil.
    @Column(name = "seuil_alerte_oeufs")
    private Integer seuilAlerteOeufs;

    @Column(name = "seuil_alerte_reforme")
    private Integer seuilAlerteReforme;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    @ManyToMany(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinTable(name = "magasin_vendeurs",
            joinColumns = @JoinColumn(name = "magasin_id"),
            inverseJoinColumns = @JoinColumn(name = "vendeur_id"))
    private List<Utilisateurs> vendeurs = new ArrayList<>();

    @Embedded
    private Initialisation initialisation;
}
