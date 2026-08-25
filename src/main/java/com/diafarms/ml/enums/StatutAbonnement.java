package com.diafarms.ml.enums;

// Statut stocké à titre de trace (mis à jour à chaque validation de paiement) — le
// statut qui compte réellement pour bloquer/débloquer l'accès web est toujours
// recalculé à la lecture à partir de Abonnement.dateFin + AbonnementConfig.
// dureeGraceHeures, jamais lu directement en base pour cette décision (voir
// AbonnementServiceImpl.calculerStatutEffectif).
public enum StatutAbonnement {
    ESSAI,
    ACTIF,
    EXPIRE
}
