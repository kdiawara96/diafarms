package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class SalairePayerRequest {
    private String employeUniqueId;
    private String periode; // "AAAA-MM"
    // Nombre de jours/heures travaillés pour cette période — obligatoire si le
    // Salaire est en mode JOURNALIER/HORAIRE (montant = tauxBase × quantite), ignoré
    // en MENSUEL. Saisi à la main : Diafarms n'a pas de système de pointage.
    private Double quantite;
    // ANCIEN champ (APK <= 1.35) : le téléphone y mettait le montant calculé avec le
    // taux de sa dernière synchro. IGNORÉ depuis 2026-10 : le serveur calcule toujours
    // le montant avec le taux de LA PÉRIODE payée (voir SalaireServiceImpl.payer).
    private Double montant;
    // Montant forcé (prime, retenue...) : seul moyen de payer autre chose que le
    // calcul de la grille. Réservé aux rôles qui gèrent les salaires (ADMIN,
    // RESPONSABLE, COMPTABLE, voir ensureCanManage), arrondi au franc.
    private Double montantForce;
    // Date du paiement (AAAA-MM-JJ), facultative : vide = jour de réception. Passé
    // permis (paiement hors ligne synchronisé plus tard), futur refusé (DateSaisie).
    private String datePaiement;
    private String description; // optionnel
}
