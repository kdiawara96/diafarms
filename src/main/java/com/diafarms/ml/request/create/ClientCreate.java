package com.diafarms.ml.request.create;

import lombok.Data;

@Data
public class ClientCreate {
    private String nom;
    private String telephone; // optionnel
    private String adresse; // optionnel
    private String email; // optionnel
}
