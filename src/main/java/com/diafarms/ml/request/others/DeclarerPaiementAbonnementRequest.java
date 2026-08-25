package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class DeclarerPaiementAbonnementRequest {
    private String periodicite; // "MENSUEL" ou "ANNUEL"
    private String moyenPaiement; // "Orange Money", "Wave", "Virement", "Espèces"...
    private String reference; // optionnel
}
