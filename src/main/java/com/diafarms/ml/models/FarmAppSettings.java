package com.diafarms.ml.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Contrôle, par ferme, quels rôles peuvent utiliser l'application mobile et/ou web,
// et pour un FINANCIER sur mobile, quelles actions précises lui sont ouvertes (vente
// œufs/réforme/fientes, entrée/sortie manuelle) — voir commons.AppAccessRules pour la
// logique de décision partagée entre AuthImpl (login), QRCodeController (génération)
// et UserStatusJwtValidator (revalidation à chaque requête pour un token issu d'un
// QR). Tout est désactivé par défaut : un rôle nouvellement créé n'a accès à rien
// tant que l'admin ne l'active pas explicitement dans Paramètres.
@Entity
@Table(name = "farm_app_settings")
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class FarmAppSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "farm_id", nullable = false, unique = true)
    private Farm farm;

    @Column(name = "producteur_mobile_enabled", nullable = false)
    private Boolean producteurMobileEnabled = false;

    @Column(name = "producteur_web_enabled", nullable = false)
    private Boolean producteurWebEnabled = false;

    @Column(name = "financier_web_enabled", nullable = false)
    private Boolean financierWebEnabled = false;

    // Un FINANCIER n'a pas de bascule mobile globale : son accès mobile se déduit de
    // ces 5 drapeaux (voir AppAccessRules.canAccessMobile) — s'ils sont tous à false,
    // il ne peut pas accéder à l'application mobile du tout.
    @Column(name = "financier_mobile_vente_oeufs", nullable = false)
    private Boolean financierMobileVenteOeufs = false;

    @Column(name = "financier_mobile_vente_reforme", nullable = false)
    private Boolean financierMobileVenteReforme = false;

    @Column(name = "financier_mobile_vente_fientes", nullable = false)
    private Boolean financierMobileVenteFientes = false;

    @Column(name = "financier_mobile_entree", nullable = false)
    private Boolean financierMobileEntree = false;

    @Column(name = "financier_mobile_sortie", nullable = false)
    private Boolean financierMobileSortie = false;
}
