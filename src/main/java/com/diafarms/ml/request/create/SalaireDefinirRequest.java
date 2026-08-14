package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class SalaireDefinirRequest {
    private String employeUniqueId;
    private String modePaiement; // "MENSUEL" | "JOURNALIER" | "HORAIRE"
    private Double tauxBase; // sens dépendant de modePaiement — voir Salaire.tauxBase
}
