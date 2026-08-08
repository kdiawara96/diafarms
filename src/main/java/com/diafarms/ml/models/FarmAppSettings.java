package com.diafarms.ml.models;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// Contrôle, par ferme, quels rôles peuvent utiliser l'application mobile et/ou web —
// voir commons.AppAccessRules pour la logique de décision partagée entre AuthImpl
// (login), QRCodeController (génération d'un QR) et UserStatusJwtValidator
// (revalidation à chaque requête pour un token issu d'un QR). Tout est désactivé par
// défaut : un rôle nouvellement créé n'a accès à rien tant que l'admin ne l'active pas
// explicitement dans Paramètres.
//
// Un seul bouton par rôle/plateforme (pas de granularité par action comme l'ancien
// FINANCIER à 5 drapeaux mobile) : depuis la refonte des rôles, chaque rôle a un jeu
// d'actions mobile fixe (COMPTABLE = entrée/sortie, VENTE = vente œufs/réforme/
// fientes), donc un simple on/off suffit — plus besoin de granularité par action.
// RESPONSABLE n'a pas de présence mobile du tout, donc pas de bascule mobile pour lui.
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

    @Column(name = "production_mobile_enabled", nullable = false)
    private Boolean productionMobileEnabled = false;

    @Column(name = "production_web_enabled", nullable = false)
    private Boolean productionWebEnabled = false;

    @Column(name = "comptable_mobile_enabled", nullable = false)
    private Boolean comptableMobileEnabled = false;

    @Column(name = "comptable_web_enabled", nullable = false)
    private Boolean comptableWebEnabled = false;

    @Column(name = "vente_mobile_enabled", nullable = false)
    private Boolean venteMobileEnabled = false;

    @Column(name = "vente_web_enabled", nullable = false)
    private Boolean venteWebEnabled = false;

    @Column(name = "responsable_web_enabled", nullable = false)
    private Boolean responsableWebEnabled = false;
}
