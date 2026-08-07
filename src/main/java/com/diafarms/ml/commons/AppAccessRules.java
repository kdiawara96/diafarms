package com.diafarms.ml.commons;

import java.util.Collection;

import com.diafarms.ml.models.FarmAppSettings;

// Logique de décision partagée par AuthImpl (login web/mobile), QRCodeController
// (génération d'un QR) et UserStatusJwtValidator (revalidation à chaque requête pour
// un token issu d'un QR) — un seul endroit pour ne jamais désynchroniser ces trois
// points d'application. Un ADMIN/SUPER_ADMIN n'est jamais concerné par ces
// restrictions ; un compte qui cumule plusieurs rôles (ex: PRODUCTEUR + FINANCIER)
// garde l'accès complet, même règle "hasOnlyRole" qu'ailleurs dans l'app (voir
// src/lib/roles.ts côté web) — un rôle supplémentaire ne doit jamais RETIRER un
// accès déjà acquis par un autre rôle.
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
        if (isOnlyRole(roles, "PRODUCTEUR")) return settings != null && Boolean.TRUE.equals(settings.getProducteurWebEnabled());
        if (isOnlyRole(roles, "FINANCIER")) return settings != null && Boolean.TRUE.equals(settings.getFinancierWebEnabled());
        return true; // cumul de rôles, ou rôle non concerné par cette restriction (ex: SUPER_ADMIN déjà couvert ci-dessus)
    }

    public static boolean canAccessMobile(FarmAppSettings settings, Collection<String> roles) {
        if (isAdmin(roles)) return true;
        if (isOnlyRole(roles, "PRODUCTEUR")) return settings != null && Boolean.TRUE.equals(settings.getProducteurMobileEnabled());
        if (isOnlyRole(roles, "FINANCIER")) return settings != null && financierHasAnyMobileAction(settings);
        return true;
    }

    public static boolean financierHasAnyMobileAction(FarmAppSettings s) {
        return s != null && (
                Boolean.TRUE.equals(s.getFinancierMobileVenteOeufs())
                || Boolean.TRUE.equals(s.getFinancierMobileVenteReforme())
                || Boolean.TRUE.equals(s.getFinancierMobileVenteFientes())
                || Boolean.TRUE.equals(s.getFinancierMobileEntree())
                || Boolean.TRUE.equals(s.getFinancierMobileSortie())
        );
    }
}
