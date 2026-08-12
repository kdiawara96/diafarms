package com.diafarms.ml.request.others;

import lombok.Data;

@Data
public class SalairePayerRequest {
    private String employeUniqueId;
    private String periode; // "AAAA-MM"
    private Double montant; // optionnel — défaut : montantMensuel du salaire de base
    private String description; // optionnel
}
