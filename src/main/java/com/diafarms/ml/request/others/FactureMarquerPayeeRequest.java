package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class FactureMarquerPayeeRequest {
    // Optionnel — si absent, on solde tout le reste dû sur cette facture.
    private Double montant;
}
