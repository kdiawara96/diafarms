package com.diafarms.ml.request.others;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PayerDetteClientRequest {
    private Double montant;
    private String description; // optionnel
}
