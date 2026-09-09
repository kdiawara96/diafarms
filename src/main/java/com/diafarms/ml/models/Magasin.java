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

// Magasin = point de STOCKAGE (dépôt intermédiaire après collecte) OU point de VENTE
// (voir type), chacun avec son propre stock (œufs/réforme). Un magasin de stockage
// reçoit directement les collectes (voir CollecteOeufs.magasinStockage) ; un magasin
// de vente reçoit des transferts explicites depuis un magasin de stockage ou
// directement un projet (voir MagasinTransfert) — remplace la répartition automatique
// proportionnelle qui existait à la vente (voir VenteOeufsImpl/VenteReformeImpl,
// désormais scopés par magasin plutôt que farm-wide). Les champs vendeurs/seuils de
// vente n'ont de sens que pour type=VENTE, seuilAlerteAlveoles que pour type=STOCKAGE
// (même convention que Batiment avant : jamais imposé en base, juste masqué côté UI).
@Entity
@Table(name = "magasins_vente")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Magasin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "unique_id", nullable = false, unique = true, length = 50)
    private String uniqueId;

    @Column(name = "nom", nullable = false, length = 100)
    private String nom;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private TypeMagasin type = TypeMagasin.VENTE;

    @Column(name = "description", length = 500)
    private String description;

    // Seuils d'alerte stock bas (nullable = alerte désactivée pour ce type dans ce
    // magasin) — configurés par l'admin/responsable à la création/modification du
    // magasin. Voir NotificationServiceImpl.addMagasinStockAlerts : notifie les
    // RESPONSABLE des projets qui contribuent actuellement au stock de CE magasin dès
    // que le stock passe sous le seuil. N'a de sens que pour type=VENTE.
    // Exprimé en ALVÉOLES (pas en œufs — plus lisible pour un usage quotidien, même
    // convention que seuilAlerteAlveoles pour un magasin de STOCKAGE), converti en
    // œufs pour comparer au disponible réel — voir NotificationServiceImpl.checkMagasinStockAlert.
    @Column(name = "seuil_alerte_oeufs")
    private Integer seuilAlerteOeufs;

    @Column(name = "seuil_alerte_reforme")
    private Integer seuilAlerteReforme;

    // Seuil d'alerte stock bas, en ALVÉOLES (pas en œufs — plus lisible pour un usage
    // quotidien) — n'a de sens que pour type=STOCKAGE. Null = alerte désactivée. Voir
    // NotificationServiceImpl.addMagasinStockageAlerts.
    @Column(name = "seuil_alerte_alveoles")
    private Integer seuilAlerteAlveoles;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false)
    private Farm farm;

    // N'a de sens que pour type=STOCKAGE : magasin de VENTE vers lequel transférer
    // automatiquement chaque collecte dès sa saisie (voir CollecteOeufsImpl.create),
    // pour qu'une petite ferme qui démarre (admin peu disponible) puisse vendre sans
    // attendre un transfert manuel — voir MagasinTransfertServiceImpl, réservé à
    // ADMIN/RESPONSABLE. Null = pas d'automatisation, transfert manuel comme avant.
    // Choix explicite, pas de déduction automatique ("un seul magasin de vente sur la
    // ferme") : reste correct même si un deuxième magasin de vente est ajouté plus tard.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "magasin_vente_par_defaut_id")
    private Magasin magasinVenteParDefaut;

    @ManyToMany(fetch = FetchType.LAZY)
    @JsonIgnore
    @JoinTable(name = "magasin_vendeurs",
            joinColumns = @JoinColumn(name = "magasin_id"),
            inverseJoinColumns = @JoinColumn(name = "vendeur_id"))
    private List<Utilisateurs> vendeurs = new ArrayList<>();

    // Emplacement (optionnel) où se trouve ce magasin — voir Site.java.
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "site_id")
    private Site site;

    @Embedded
    private Initialisation initialisation;

    public enum TypeMagasin {
        VENTE, STOCKAGE
    }
}
