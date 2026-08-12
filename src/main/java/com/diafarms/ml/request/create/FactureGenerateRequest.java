package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class FactureGenerateRequest {
    private String sourceType; // "VENTE_OEUFS" | "VENTE_REFORME" | "COMMANDE"
    private String sourceUniqueId;
}
