package com.diafarms.ml.commons;

import java.util.Collection;

import com.diafarms.ml.models.FarmAppSettings;

// Logique de décision partagée par AuthImpl (login web/mobile), QRCodeController
// (génération d'un QR) et UserStatusJwtValidator (revalidation à chaque requête pour
// un token issu d'un QR) — un seul endroit pour ne jamais désynchroniser ces trois
// points d'application. Un ADMIN/SUPER_ADMIN n'est jamais concerné par ces
// restrictions ; un compte qui cumule plusieurs rôles (ex: PRODUCTION + COMPTABLE)
// garde l'accès complet, même règle "hasOnlyRole" qu'ailleurs dans l'app (voir
// src/lib/roles.ts côté web) — un rôle supplémentaire ne doit jamais RETIRER un
// accès déjà acquis par un autre rôle. RESPONSABLE n'a pas de présence mobile du
// tout (pas de bascule dédiée, voir FarmAppSettings) : un RESPONSABLE pur ne peut
// jamais utiliser le mobile, quoi que configure l'admin.
public final class AppAccessRules {

    private AppAccessRules() {
    }

    private static boolean isOnlyRole(Collection<String> roles, String role) {
        return roles != null && !roles.isEmpty() && roles.stream().allMatch(r -> role.equalsIgnoreCase(r));
    }

    private static boolean isAdmin(Collection<String> roles) {
        return roles != null && roles.stream().anyMatch(r -> "ADMIN".equalsIgnoreCase(r) || "SUPER_ADMIN".equalsIgnoreCase(r));
    }

    public static boolean canAccessWeb(FarmAppSettings settings, Collection<String> roles) {
        if (isAdmin(roles)) return true;
        if (isOnlyRole(roles, "PRODUCTION")) return settings != null && Boolean.TRUE.equals(settings.getProductionWebEnabled());
        if (isOnlyRole(roles, "COMPTABLE")) return settings != null && Boolean.TRUE.equals(settings.getComptableWebEnabled());
        if (isOnlyRole(roles, "VENTE")) return settings != null && Boolean.TRUE.equals(settings.getVenteWebEnabled());
        if (isOnlyRole(roles, "RESPONSABLE")) return settings != null && Boolean.TRUE.equals(settings.getResponsableWebEnabled());
        return true; // cumul de rôles, ou rôle non concerné par cette restriction (ex: SUPER_ADMIN déjà couvert ci-dessus)
    }

    public static boolean canAccessMobile(FarmAppSettings settings, Collection<String> roles) {
        if (isAdmin(roles)) return true;
        if (isOnlyRole(roles, "PRODUCTION")) return settings != null && Boolean.TRUE.equals(settings.getProductionMobileEnabled());
        if (isOnlyRole(roles, "COMPTABLE")) return settings != null && Boolean.TRUE.equals(settings.getComptableMobileEnabled());
        if (isOnlyRole(roles, "VENTE")) return settings != null && Boolean.TRUE.equals(settings.getVenteMobileEnabled());
        if (isOnlyRole(roles, "RESPONSABLE")) return false; // jamais de mobile pour ce rôle, voir commentaire de classe
        return true;
    }
}
