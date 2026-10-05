package com.diafarms.ml.request.others;

import lombok.Data;

// Console SUPER_ADMIN, « Activer / prolonger » : soit une durée (mois et/ou jours,
// ajoutés à l'échéance actuelle), soit une date de fin explicite. Paiement facultatif :
// argent reçu hors application, enregistré directement comme VALIDE.
@Data
public class AdminActiverAbonnementRequest {
    private String periodicite;   // MENSUEL | ANNUEL
    private Integer mois;
    private Integer jours;
    private String dateFin;       // yyyy-MM-dd, prioritaire sur mois/jours
    private Double montant;       // facultatif : paiement reçu hors application
    private String moyenPaiement;
    private String reference;
}
