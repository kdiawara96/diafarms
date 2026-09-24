package com.diafarms.ml.request.others;

import lombok.Data;

// Corps de POST /factures/{uid}/paiement — voir FactureServiceImpl.payer.
@Data
public class FacturePaiementRequest {
    // Optionnel — si absent (ou <= 0), on solde tout le reste dû sur cette facture.
    private Double montant;
    // Optionnel — ESPECES par défaut (voir ModePaiement).
    private String mode;
    // Optionnel, format ISO (yyyy-MM-dd) — aujourd'hui par défaut.
    private String date;
}
