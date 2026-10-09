package com.diafarms.ml.request.others;

import lombok.Data;

// Console SUPER_ADMIN, « Recharger » (crédit prépayé) : argent reçu hors application,
// enregistré comme une recharge VALIDÉE. montant et moyenPaiement obligatoires ; périodicité,
// durée et date de fin ne sont plus utilisées (gardées pour les anciens clients).
// requestId : identifiant de l'envoi (le web en crée un par formulaire) ; un même envoi
// rejoué ne crédite jamais deux fois.
@Data
public class AdminActiverAbonnementRequest {
    private String periodicite;   // MENSUEL | ANNUEL
    private Integer mois;
    private Integer jours;
    private String dateFin;       // yyyy-MM-dd, prioritaire sur mois/jours
    private Double montant;       // facultatif : paiement reçu hors application
    private String moyenPaiement;
    private String reference;
    private String requestId;
}
