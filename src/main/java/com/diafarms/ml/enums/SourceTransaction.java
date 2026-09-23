package com.diafarms.ml.enums;

public enum SourceTransaction {
    MANUEL,
    VENTE_OEUFS,
    VENTE_REFORME,
    SALAIRE,
    ALIMENTATION,
    SOINS,
    VACCINATION,
    INVESTISSEMENT,
    PROJET_ACHAT_SUJETS,
    PROJET_CHARGES,
    // Vente de fientes ou "autre vente" (voir VenteDiverse) — une seule transaction
    // commune par vente, sourceUniqueId = VenteDiverse.uniqueId.
    VENTE_DIVERSE,
    // Paiement d'un client (voir PaiementClient) — sourceUniqueId = uniqueId du paiement.
    PAIEMENT_CLIENT,
    // Remboursement au client (voir RemboursementClient) — 17 caractères, varchar(20).
    REMBOURSEMENT_CLI
}
